package dev.aiops.server.controller.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import dev.aiops.server.security.ApiKeyHasher;
import dev.aiops.server.service.InvitationGoneException;
import dev.aiops.server.service.InvitationService;
import dev.aiops.server.support.ApiSession;
import dev.aiops.server.support.PostgresContainerSupport;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

/**
 * 조장 초대 링크 발급·조회 (AI-84). 진짜 서버 + 테스트 세션. 관리자 GitHub id 9401, 일반 사용자 9402.
 * 수락은 14단계가 만든 InvitationService.accept 를 그대로 불러 "1회용"과 "이전 링크 무효"를 확인한다.
 */
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"aiops.admin.github-ids=9401", "aiops.console.base-url=https://app.example.test"})
class OwnerInviteTest extends PostgresContainerSupport {

    @Value("${local.server.port}")
    int port;

    @Autowired InvitationService invitationService;

    private final JsonMapper json = JsonMapper.builder().build();
    private JdbcTemplate owner;
    private long admin;
    private long normalUser;
    private long captain;
    private long other;
    private long team;

    @BeforeEach
    void seed() {
        owner = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), OWNER_USER, OWNER_PASSWORD));
        owner.execute("TRUNCATE membership, invitation, admin_access_log, app_user, cohort, error_event, error_group, "
                + "app_snapshot, ingest_stat, heartbeat_last, api_key, tenant RESTART IDENTITY CASCADE");
        admin = owner.queryForObject("INSERT INTO app_user (github_id, login) VALUES (9401, 'admin') RETURNING id", Long.class);
        normalUser = owner.queryForObject("INSERT INTO app_user (github_id, login) VALUES (9402, 'user') RETURNING id", Long.class);
        captain = owner.queryForObject("INSERT INTO app_user (github_id, login) VALUES (9403, 'captain') RETURNING id", Long.class);
        other = owner.queryForObject("INSERT INTO app_user (github_id, login) VALUES (9404, 'other') RETURNING id", Long.class);
        team = owner.queryForObject("INSERT INTO tenant (name, status) VALUES ('3기-1조', 'WAITING_OWNER') RETURNING id", Long.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(HttpResponse<String> res) {
        return json.readValue(res.body(), Map.class);
    }

    private String issue(ApiSession session, long teamId) {
        HttpResponse<String> res = session.post("/api/admin/teams/" + teamId + "/owner-invite", "{}");
        assertThat(res.statusCode()).isEqualTo(201);
        return rawOf(map(res));
    }

    // 링크 "https://app.example.test/invite/<원문>" 에서 원문만 꺼낸다
    private static String rawOf(Map<String, Object> body) {
        String url = (String) body.get("inviteUrl");
        assertThat(url).startsWith("https://app.example.test/invite/");
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private long invitationCount() {
        return owner.queryForObject("SELECT count(*) FROM invitation", Long.class);
    }

    @Test
    void 조장_대기_팀에_링크를_발급하면_201과_링크_DB에는_해시만() {
        Instant before = Instant.now();
        HttpResponse<String> res = ApiSession.login(port, admin).post("/api/admin/teams/" + team + "/owner-invite", "{}");

        assertThat(res.statusCode()).isEqualTo(201);
        Map<String, Object> body = map(res);
        String raw = rawOf(body);
        assertThat(raw).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(Instant.parse((String) body.get("expiresAt")))
                .isBetween(before.plus(Duration.ofDays(7)), Instant.now().plus(Duration.ofDays(7)));

        Map<String, Object> row = owner.queryForMap("SELECT * FROM invitation");
        assertThat(row.get("token_hash")).isEqualTo(ApiKeyHasher.sha256Hex(raw));
        assertThat(row.toString()).doesNotContain(raw);
        assertThat(row.get("role")).isEqualTo("OWNER");
        assertThat(((Number) row.get("max_uses")).intValue()).isEqualTo(1);
        assertThat(((Number) row.get("used_count")).intValue()).isZero();
        assertThat(((Number) row.get("tenant_id")).longValue()).isEqualTo(team);
        assertThat(((Number) row.get("created_by")).longValue()).isEqualTo(admin);
    }

    @Test
    void 원문은_응답_밖_어디에도_없다_DB_접근기록_로그(CapturedOutput output) {
        ApiSession session = ApiSession.login(port, admin);
        String raw = issue(session, team);
        session.get("/api/admin/teams/" + team + "/owner-invite");

        // 접근 기록은 응답이 나간 직후 저장되므로 잠깐 기다린 뒤 확인
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(owner.queryForList("SELECT action FROM admin_access_log", String.class))
                        .anyMatch(a -> a.contains("owner-invite")));
        assertThat(owner.queryForList("SELECT action FROM admin_access_log", String.class))
                .noneMatch(a -> a.contains(raw));
        assertThat(owner.queryForMap("SELECT * FROM invitation").toString()).doesNotContain(raw);
        assertThat(output.getAll()).doesNotContain(raw);
    }

    @Test
    void 이미_조장이_있는_팀과_정지된_팀은_409이고_링크를_만들지_않는다() {
        ApiSession session = ApiSession.login(port, admin);
        long active = owner.queryForObject("INSERT INTO tenant (name, status) VALUES ('active', 'ACTIVE') RETURNING id", Long.class);
        long suspended = owner.queryForObject("INSERT INTO tenant (name, status) VALUES ('susp', 'SUSPENDED') RETURNING id", Long.class);

        HttpResponse<String> a = session.post("/api/admin/teams/" + active + "/owner-invite", "{}");
        HttpResponse<String> s = session.post("/api/admin/teams/" + suspended + "/owner-invite", "{}");

        assertThat(a.statusCode()).isEqualTo(409);
        assertThat(map(a)).containsEntry("error", "TEAM_ALREADY_HAS_OWNER");
        assertThat(s.statusCode()).isEqualTo(409);
        assertThat(invitationCount()).isZero();
    }

    @Test
    void 없는_팀은_404() {
        ApiSession session = ApiSession.login(port, admin);

        HttpResponse<String> post = session.post("/api/admin/teams/9999/owner-invite", "{}");
        HttpResponse<String> get = session.get("/api/admin/teams/9999/owner-invite");

        assertThat(post.statusCode()).isEqualTo(404);
        assertThat(map(post)).containsEntry("error", "TEAM_NOT_FOUND");
        assertThat(get.statusCode()).isEqualTo(404);
    }

    @Test
    void 다시_발급하면_이전_링크는_무효_새_링크는_1회만_쓸_수_있다() {
        ApiSession session = ApiSession.login(port, admin);
        String first = issue(session, team);
        String second = issue(session, team);

        assertThat(second).isNotEqualTo(first);
        // 이전 링크: 만료 처리돼 수락 불가
        assertThatThrownBy(() -> invitationService.accept(first, captain)).isInstanceOf(InvitationGoneException.class);
        // 새 링크: 첫 사용은 성공 → 조장 합류, 팀 ACTIVE
        assertThat(invitationService.accept(second, captain).role()).isEqualTo("OWNER");
        assertThat(owner.queryForObject("SELECT status FROM tenant WHERE id = ?", String.class, team)).isEqualTo("ACTIVE");
        // 두 번째 사용 거부 (다른 사람)
        assertThatThrownBy(() -> invitationService.accept(second, other)).isInstanceOf(InvitationGoneException.class);
        assertThat(owner.queryForObject("SELECT count(*) FROM membership WHERE tenant_id = ?", Long.class, team)).isEqualTo(1);
    }

    @Test
    void 조회는_유효한_링크의_만료시각과_사용여부만_주고_원문은_없다() {
        ApiSession session = ApiSession.login(port, admin);
        assertThat(session.get("/api/admin/teams/" + team + "/owner-invite").statusCode()).isEqualTo(404);   // 아직 발급 전

        String raw = issue(session, team);
        HttpResponse<String> before = session.get("/api/admin/teams/" + team + "/owner-invite");
        assertThat(before.statusCode()).isEqualTo(200);
        assertThat(map(before)).containsOnlyKeys("expiresAt", "used").containsEntry("used", false);
        assertThat(before.body()).doesNotContain(raw);

        invitationService.accept(raw, captain);
        assertThat(map(session.get("/api/admin/teams/" + team + "/owner-invite"))).containsEntry("used", true);
    }

    @Test
    void 관리자가_아니면_발급도_조회도_403() {
        ApiSession user = ApiSession.login(port, normalUser);

        assertThat(user.post("/api/admin/teams/" + team + "/owner-invite", "{}").statusCode()).isEqualTo(403);
        assertThat(user.get("/api/admin/teams/" + team + "/owner-invite").statusCode()).isEqualTo(403);
        assertThat(invitationCount()).isZero();
    }
}