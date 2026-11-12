package dev.aiops.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import dev.aiops.protocol.ProtocolConstants;
import dev.aiops.server.security.ApiKeyHasher;
import dev.aiops.server.support.ApiSession;
import dev.aiops.server.support.PostgresContainerSupport;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

/**
 * 팀 정지·재개 (AI-85). 진짜 서버 + 테스트 세션. 관리자 GitHub id 9501, 팀 조장 9502.
 * 디스코드는 가짜(MockitoBean)로 바꿔 "언제 몇 번 불렸나"만 본다 (커밋 뒤 @Async 리스너가 부르므로 timeout 으로 기다림).
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "aiops.admin.github-ids=9501")
class SuspendTest extends PostgresContainerSupport {

    private static final String RAW_KEY = "suspend-test-key";
    private static final String EVENTS_JSON = "{\"env\":\"local\","
            + "\"app\":{\"name\":\"sample\",\"commit\":\"abc\",\"hostHash\":\"abc123def456\"},"
            + "\"events\":[{\"time\":1700000000000,\"level\":\"ERROR\","
            + "\"exceptionType\":\"java.lang.NullPointerException\",\"message\":\"boom\",\"thread\":\"main\"}],"
            + "\"droppedCount\":0}";

    @Value("${local.server.port}")
    int port;

    @MockitoBean DiscordProvisioner provisioner;

    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();
    private JdbcTemplate owner;
    private long admin;
    private long captain;
    private long team;

    @BeforeEach
    void seed() {
        owner = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), OWNER_USER, OWNER_PASSWORD));
        owner.execute("TRUNCATE membership, invitation, admin_access_log, app_user, cohort, error_event, error_group, "
                + "app_snapshot, ingest_stat, heartbeat_last, api_key, tenant RESTART IDENTITY CASCADE");
        admin = owner.queryForObject("INSERT INTO app_user (github_id, login) VALUES (9501, 'admin') RETURNING id", Long.class);
        captain = owner.queryForObject("INSERT INTO app_user (github_id, login) VALUES (9502, 'captain') RETURNING id", Long.class);
        team = owner.queryForObject("INSERT INTO tenant (name, status) VALUES ('3기-1조', 'ACTIVE') RETURNING id", Long.class);
        owner.update("INSERT INTO membership (user_id, tenant_id, role) VALUES (?, ?, 'OWNER')", captain, team);
        owner.update("INSERT INTO api_key (tenant_id, key_hash, env) VALUES (?, ?, 'local')", team, ApiKeyHasher.sha256Hex(RAW_KEY));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(HttpResponse<String> res) {
        return json.readValue(res.body(), Map.class);
    }

    // 그 팀 키로 Starter 처럼 에러 묶음을 보낸다 → 상태 코드
    private int sendEvents() {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + ProtocolConstants.PATH_EVENTS))
                .header(ProtocolConstants.HEADER_API_KEY, RAW_KEY)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(EVENTS_JSON))
                .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private HttpResponse<String> suspend(ApiSession session, long teamId) {
        return session.post("/api/admin/teams/" + teamId + "/suspend", "{}");
    }

    private HttpResponse<String> resume(ApiSession session, long teamId) {
        return session.post("/api/admin/teams/" + teamId + "/resume", "{}");
    }

    private String statusOf(long teamId) {
        return owner.queryForObject("SELECT status FROM tenant WHERE id = ?", String.class, teamId);
    }

    @Test
    void 정지_직후_그_팀_키로_오는_요청은_401_캐시가_따뜻해도() {
        ApiSession session = ApiSession.login(port, admin);
        assertThat(sendEvents()).isEqualTo(202);   // 키가 캐시에 들어간다 (60초 동안 DB를 안 봄)

        HttpResponse<String> res = suspend(session, team);

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(map(res)).containsEntry("status", "SUSPENDED");
        assertThat(((Number) map(res).get("teamId")).longValue()).isEqualTo(team);
        assertThat(sendEvents()).isEqualTo(401);   // 캐시가 지워져 DB를 다시 보고 정지를 안다
    }

    @Test
    void 재개하면_같은_키로_다시_202_키는_지워지거나_폐기되지_않는다() {
        ApiSession session = ApiSession.login(port, admin);
        suspend(session, team);
        assertThat(sendEvents()).isEqualTo(401);
        assertThat(owner.queryForObject("SELECT count(*) FROM api_key WHERE tenant_id = ? AND revoked_at IS NULL",
                Long.class, team)).isEqualTo(1);   // 정지 중에도 키 행은 그대로

        HttpResponse<String> res = resume(session, team);

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(map(res)).containsEntry("status", "ACTIVE");   // 조장이 있으니 ACTIVE
        assertThat(sendEvents()).isEqualTo(202);
    }

    @Test
    void 조장이_없던_팀은_재개하면_WAITING_OWNER로_돌아간다() {
        long waiting = owner.queryForObject("INSERT INTO tenant (name, status) VALUES ('조장 대기', 'WAITING_OWNER') RETURNING id", Long.class);
        ApiSession session = ApiSession.login(port, admin);
        assertThat(map(suspend(session, waiting))).containsEntry("status", "SUSPENDED");

        HttpResponse<String> res = resume(session, waiting);

        assertThat(map(res)).containsEntry("status", "WAITING_OWNER");
        assertThat(statusOf(waiting)).isEqualTo("WAITING_OWNER");
    }

    @Test
    void 정지된_팀의_멤버는_팀_화면이_403_이유는_SUSPENDED() {
        ApiSession member = ApiSession.login(port, captain);
        assertThat(member.get("/api/teams/" + team + "/members").statusCode()).isEqualTo(200);   // 정지 전엔 열림

        suspend(ApiSession.login(port, admin), team);

        HttpResponse<String> res = member.get("/api/teams/" + team + "/members");
        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(map(res)).containsEntry("reason", "SUSPENDED");
    }

    @Test
    void 두_번_정지해도_200이고_디스코드_정지는_한_번만_요청된다() {
        ApiSession session = ApiSession.login(port, admin);

        assertThat(suspend(session, team).statusCode()).isEqualTo(200);
        verify(provisioner, timeout(5000).times(1)).suspend(team);
        assertThat(suspend(session, team).statusCode()).isEqualTo(200);

        verify(provisioner, after(1000).times(1)).suspend(team);   // 두 번째 정지는 아무것도 안 했다
        assertThat(statusOf(team)).isEqualTo("SUSPENDED");
    }

    @Test
    void 재개하면_디스코드_채널_생성이_다시_요청된다() {
        ApiSession session = ApiSession.login(port, admin);
        suspend(session, team);

        resume(session, team);

        verify(provisioner, timeout(5000).times(1)).provision(team);
    }

    @Test
    void 없는_팀은_404_관리자가_아니면_403() {
        assertThat(suspend(ApiSession.login(port, admin), 9999).statusCode()).isEqualTo(404);
        assertThat(resume(ApiSession.login(port, admin), 9999).statusCode()).isEqualTo(404);

        ApiSession notAdmin = ApiSession.login(port, captain);
        assertThat(suspend(notAdmin, team).statusCode()).isEqualTo(403);
        assertThat(resume(notAdmin, team).statusCode()).isEqualTo(403);
        assertThat(statusOf(team)).isEqualTo("ACTIVE");
    }

    @Test
    void 정지_호출은_접근_기록에_팀_번호와_함께_남는다() {
        suspend(ApiSession.login(port, admin), team);

        // 기록은 응답이 나간 직후 저장되므로 잠깐 기다리며 확인
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(owner.queryForList("SELECT tenant_id FROM admin_access_log WHERE action LIKE '%suspend%'", Long.class))
                        .containsExactly(team));
    }
}