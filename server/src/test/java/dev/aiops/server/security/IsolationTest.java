package dev.aiops.server.security;

import dev.aiops.protocol.ProtocolConstants;
import dev.aiops.server.model.entity.ErrorEventEntity;
import dev.aiops.server.model.repository.ErrorEventRepository;
import dev.aiops.server.support.PostgresContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 🔴 설계자 선행 테스트 (D-080, AI-69). 담당자는 이 파일을 수정하지 않는다 (규칙 9).
 * 격리는 코드가 아니라 DB가 강제한다 (규칙 11) — 그래서 "WHERE를 붙였나"가 아니라 "DB가 0건을 주나"를 본다.
 *
 * - 서버(테스트 대상)는 aiops_app으로 붙는다. 시드·결과 확인만 owner(RLS 무시)로 한다.
 * - 커넥션 풀 크기 1: 4번(같은 연결 재사용) 확인용.
 * - 6·8번은 HTTP(POST /v1/events)로만 검사한다. 2단계 수집·6단계 처리 클래스를 직접 부르지 않으므로
 * 지금도 컴파일되고, A의 작업이 develop에 들어온 뒤 초록이 된다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.datasource.hikari.maximum-pool-size=1")
class IsolationTest extends PostgresContainerSupport {

    private static final String KEY_A = "aiops_l_isolation-team-a-0001";
    private static final String KEY_B = "aiops_l_isolation-team-b-0001";

    @Autowired
    ErrorEventRepository events;
    @Autowired
    PlatformTransactionManager txManager;
    @Value("${local.server.port}")
    int port;

    private JdbcTemplate owner;
    private TransactionTemplate tx;
    private long teamA;
    private long teamB;

    // ---- 준비: 팀 A·B, 팀마다 키 1개, 이벤트 2건씩 (owner로 넣는다) ----

    @BeforeEach
    void seed() {
        owner = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), OWNER_USER, OWNER_PASSWORD));
        tx = new TransactionTemplate(txManager);

        owner.execute(
                "TRUNCATE error_event, error_group, app_snapshot, ingest_stat, heartbeat_last, api_key, tenant RESTART IDENTITY CASCADE");

        teamA = owner.queryForObject("INSERT INTO tenant (name) VALUES ('팀 A') RETURNING id", Long.class);
        teamB = owner.queryForObject("INSERT INTO tenant (name) VALUES ('팀 B') RETURNING id", Long.class);

        owner.update("INSERT INTO api_key (tenant_id, key_hash, env) VALUES (?, ?, 'local')", teamA, sha256(KEY_A));
        owner.update("INSERT INTO api_key (tenant_id, key_hash, env) VALUES (?, ?, 'local')", teamB, sha256(KEY_B));

        for (long team : List.of(teamA, teamB)) {
            for (int i = 0; i < 2; i++) {
                owner.update("INSERT INTO error_event (tenant_id, env, payload) VALUES (?, 'local', '{}'::jsonb)",
                        team);
            }
        }
    }

    @AfterEach
    void cleanThread() {
        try {
            TenantContext.clear();
        } catch (UnsupportedOperationException ignored) {
            // AI-70 구현 전
        }
    }

    // ---- 1. 다른 팀 데이터 읽기 → 0건 ----

    @Test
    void readOtherTeamReturnsNothing() {
        Long bEventId = owner.queryForObject(
                "SELECT id FROM error_event WHERE tenant_id = ? ORDER BY id LIMIT 1", Long.class, teamB);

        TenantContext.set(teamA);
        var found = tx.execute(s -> events.findById(bEventId));

        assertThat(found).isEmpty();
    }

    // ---- 2. 다른 팀 번호로 쓰기 → DB가 거부 ----

    @Test
    void writeOtherTeamIdRejected() {
        TenantContext.set(teamA); // 단언 밖에서 설정 (구현 전 예외가 "거부"로 오인되지 않게)

        assertThatThrownBy(() -> tx.execute(s -> events.saveAndFlush(
                new ErrorEventEntity(teamB, "local", "{}", "app", null, "h"))))
                .isInstanceOf(RuntimeException.class);

        Long bCount = owner.queryForObject("SELECT count(*) FROM error_event WHERE tenant_id = ?", Long.class, teamB);
        assertThat(bCount).isEqualTo(2L); // B팀 행은 그대로
    }

    // ---- 3. 팀 설정 없이 조회 → 0건 (fail closed) ----

    @Test
    void noTenantReturnsNothing() {
        TenantContext.clear();

        Long count = tx.execute(s -> events.count());

        assertThat(count).isZero();
    }

    // ---- 4. 같은 DB 연결(풀 1)로 A → B 연속 → 섞이지 않음 ----

    @Test
    void sameConnectionSequentialTeamsNotMixed() {
        TenantContext.set(teamA);
        List<ErrorEventEntity> first = tx.execute(s -> events.findAll());
        TenantContext.clear();

        TenantContext.set(teamB);
        List<ErrorEventEntity> second = tx.execute(s -> events.findAll());
        TenantContext.clear();

        assertThat(first).hasSize(2).allMatch(e -> e.getTenantId().equals(teamA));
        assertThat(second).hasSize(2).allMatch(e -> e.getTenantId().equals(teamB));
    }

    // ---- 5. 같은 스레드에서 A 요청 → B 요청 → 팀 없는 요청 ----

    @Test
    void sameThreadSequentialTeamsNotMixed() {
        // 요청 하나 = set → 트랜잭션 → finally clear (TenantFilter가 하는 일을 흉내)
        List<ErrorEventEntity> a = asRequest(teamA);
        List<ErrorEventEntity> b = asRequest(teamB);

        TenantContext.clear();
        Long leftover = tx.execute(s -> events.count()); // 다음 요청이 팀 없이 들어오면 앞 팀이 남아 있으면 안 된다

        assertThat(a).allMatch(e -> e.getTenantId().equals(teamA));
        assertThat(b).hasSize(2).allMatch(e -> e.getTenantId().equals(teamB));
        assertThat(leftover).isZero();
    }

    private List<ErrorEventEntity> asRequest(long team) {
        TenantContext.set(team);
        try {
            return tx.execute(s -> events.findAll());
        } finally {
            TenantContext.clear();
        }
    }

    // ---- 6. 처리 스레드 경로도 팀별로 반영 (HTTP 수집 → @Async 처리 → 그룹) ----

    @Test
    void processingThreadAppliesTenant() throws Exception {
        Long aGroupsBefore = owner.queryForObject("SELECT count(*) FROM error_group WHERE tenant_id = ?", Long.class,
                teamA);

        HttpResponse<String> res = postEvents(KEY_B);
        assertThat(res.statusCode()).isEqualTo(202);

        long bGroups = waitForGroups(teamB, Duration.ofSeconds(10));
        Long aGroupsAfter = owner.queryForObject("SELECT count(*) FROM error_group WHERE tenant_id = ?", Long.class,
                teamA);

        assertThat(bGroups).isGreaterThan(0); // B 그룹이 처리 스레드에서 만들어짐
        assertThat(aGroupsAfter).isEqualTo(aGroupsBefore); // A는 변화 없음
    }

    // ---- 7. 서버 접속 계정은 테이블 소유자가 아니고 RLS 우회 권한도 없다 ----

    @Test
    void appRoleIsNotTableOwner() {
        String tableOwner = owner.queryForObject(
                "SELECT tableowner FROM pg_tables WHERE tablename = 'error_event'", String.class);
        Boolean bypass = owner.queryForObject(
                "SELECT rolbypassrls FROM pg_roles WHERE rolname = 'aiops_app'", Boolean.class);

        assertThat(tableOwner).isNotEqualTo("aiops_app");
        assertThat(bypass).isFalse();
    }

    // ---- 8. 폐기된 키 → 401 ----

    @Test
    void revokedKeyReturns401() throws Exception {
        owner.update("UPDATE api_key SET revoked_at = now() WHERE key_hash = ?", sha256(KEY_A));

        HttpResponse<String> res = postEvents(KEY_A);

        assertThat(res.statusCode()).isEqualTo(401);
    }

    // ---- 도우미 ----

    private HttpResponse<String> postEvents(String key) throws Exception {
        String body = """
                {"env":"local",
                 "app":{"name":"isolation-test","commit":null,"dirty":null,"hostHash":"h1"},
                 "events":[{"time":%d,"level":"ERROR","exceptionType":"java.lang.NullPointerException",
                            "message":"isolation","stack":[{"className":"dev.shop.OrderService","methodName":"place","fileName":"OrderService.java","lineNumber":42}],
                            "causes":[],"thread":"main","phase":"RUNTIME"}],
                 "droppedCount":0}
                """
                .formatted(System.currentTimeMillis());

        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + ProtocolConstants.PATH_EVENTS))
                .header("Content-Type", "application/json")
                .header(ProtocolConstants.HEADER_API_KEY, key)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(5))
                .build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
    }

    private long waitForGroups(long team, Duration max) throws InterruptedException {
        long deadline = System.currentTimeMillis() + max.toMillis();
        long count = 0;
        while (System.currentTimeMillis() < deadline) {
            count = owner.queryForObject("SELECT count(*) FROM error_group WHERE tenant_id = ?", Long.class, team);
            if (count > 0) {
                return count;
            }
            Thread.sleep(200);
        }
        return count;
    }

    private static String sha256(String raw) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}