package dev.aiops.server.controller.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aiops.server.security.TenantContext;
import dev.aiops.server.service.AdminApiException;
import dev.aiops.server.service.ErrorGroupQueryService;
import dev.aiops.server.support.ApiSession;
import dev.aiops.server.support.PostgresContainerSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

/**
 * 관리자 전용 에러 조회 (AI-107, D-103). 진짜 서버로 관리자·비관리자 세션을 만들어 본다.
 * 관리자 admin(github 9901), 팀 A 조장 alice, 팀 B 조장 dave.
 * 팀 A: gA1(설명 있음, 원본 1건), gA2 / 팀 B: gB1.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "aiops.admin.github-ids=9901")
class AdminErrorControllerTest extends PostgresContainerSupport {

    @Value("${local.server.port}")
    int port;

    @Autowired
    AdminErrorController controller;

    private final JsonMapper json = JsonMapper.builder().build();
    private JdbcTemplate owner;
    private long teamA;
    private long teamB;
    private long admin;
    private long alice;
    private long dave;
    private long gA1;
    private long gA2;
    private long gB1;

    @BeforeEach
    void seed() {
        owner = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), OWNER_USER, OWNER_PASSWORD));
        owner.execute("TRUNCATE membership, invitation, admin_access_log, app_user, cohort, error_event, error_group, "
                + "app_snapshot, ingest_stat, heartbeat_last, api_key, tenant RESTART IDENTITY CASCADE");
        teamA = owner.queryForObject("INSERT INTO tenant (name) VALUES ('팀 A') RETURNING id", Long.class);
        teamB = owner.queryForObject("INSERT INTO tenant (name) VALUES ('팀 B') RETURNING id", Long.class);
        admin = user(9901, "admin");
        alice = user(9902, "alice");
        dave = user(9904, "dave");
        owner.update("INSERT INTO membership (user_id, tenant_id, role) VALUES (?, ?, 'OWNER')", alice, teamA);
        owner.update("INSERT INTO membership (user_id, tenant_id, role) VALUES (?, ?, 'OWNER')", dave, teamB);

        gA1 = group(teamA, 1, "원인 후보: 가짜 설명");
        gA2 = group(teamA, 2, null);
        gB1 = group(teamB, 3, "원인 후보: 다른 팀 설명");
        event(teamA, gA1, "boom-A");
    }

    private long user(long githubId, String login) {
        return owner.queryForObject("INSERT INTO app_user (github_id, login) VALUES (?, ?) RETURNING id",
                Long.class, githubId, login);
    }

    private long group(long tenantId, int n, String explanation) {
        return owner.queryForObject("INSERT INTO error_group (tenant_id, fingerprint, exception_type, top_frame, "
                + "first_env, occurrence_count, ai_explanation, ai_explained_at) "
                + "VALUES (?, ?, 'java.lang.IllegalStateException', 'dev.shop.OrderService#place', 'local', 3, "
                + "CAST(? AS text), CASE WHEN CAST(? AS text) IS NULL THEN NULL ELSE now() END) RETURNING id",
                Long.class, tenantId, String.format("%064d", n), explanation, explanation);
    }

    private void event(long tenantId, long groupId, String message) {
        String payload = "{\"time\":1700000000000,\"level\":\"ERROR\","
                + "\"exceptionType\":\"java.lang.IllegalStateException\",\"message\":\"" + message + "\","
                + "\"stack\":[{\"className\":\"dev.shop.OrderService\",\"methodName\":\"place\","
                + "\"fileName\":\"OrderService.java\",\"lineNumber\":48}],\"causes\":[],\"thread\":\"main\"}";
        owner.update("INSERT INTO error_event (tenant_id, env, group_id, payload, received_at, processed) "
                + "VALUES (?, 'local', ?, CAST(? AS jsonb), now(), true)", tenantId, groupId, payload);
    }

    private String errors(long teamId) {
        return "/api/admin/teams/" + teamId + "/errors";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(HttpResponse<String> res) {
        return json.readValue(res.body(), Map.class);
    }

    // 목록 응답(content)의 그룹 번호들
    @SuppressWarnings("unchecked")
    private List<Long> ids(HttpResponse<String> res) {
        List<Map<String, Object>> content = (List<Map<String, Object>>) map(res).get("content");
        return content.stream().map(g -> ((Number) g.get("id")).longValue()).collect(Collectors.toList());
    }

    // ---- 조회 ----

    @Test
    void 관리자는_팀_A_B_를_각각_조회하고_다른_팀_그룹은_섞이지_않는다() {
        ApiSession session = ApiSession.login(port, admin);

        HttpResponse<String> a = session.get(errors(teamA));
        HttpResponse<String> b = session.get(errors(teamB));

        assertThat(a.statusCode()).isEqualTo(200);
        assertThat(ids(a)).containsExactlyInAnyOrder(gA1, gA2);   // A 를 조회할 때 B 의 그룹은 0건
        assertThat(b.statusCode()).isEqualTo(200);
        assertThat(ids(b)).containsExactly(gB1);
    }

    @Test
    void 목록은_팀용과_같은_조건을_받는다() {
        ApiSession session = ApiSession.login(port, admin);

        HttpResponse<String> sorted = session.get(errors(teamA) + "?env=local&status=OPEN&sort=count,desc&page=0&size=1");
        HttpResponse<String> badSize = session.get(errors(teamA) + "?size=101");

        assertThat(sorted.statusCode()).isEqualTo(200);
        assertThat(ids(sorted)).hasSize(1);
        assertThat(((Number) map(sorted).get("totalElements")).longValue()).isEqualTo(2);
        assertThat(badSize.statusCode()).isEqualTo(400);
        assertThat(map(badSize).get("error")).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void 관리자는_팀의_그룹_상세를_본다() {
        HttpResponse<String> res = ApiSession.login(port, admin).get(errors(teamA) + "/" + gA1);

        assertThat(res.statusCode()).isEqualTo(200);
        Map<String, Object> body = map(res);
        assertThat(((Number) body.get("id")).longValue()).isEqualTo(gA1);
        assertThat(body.get("aiExplanation")).isEqualTo("원인 후보: 가짜 설명");
        assertThat((List<?>) body.get("recentEvents")).hasSize(1);
        assertThat((List<?>) body.get("dailyCounts")).hasSize(14);
        assertThat(res.body()).doesNotContain("hostHash").doesNotContain("host_hash").doesNotContain("webhook");
    }

    @Test
    void 다른_팀_그룹을_팀_주소로_부르면_404() {
        ApiSession session = ApiSession.login(port, admin);

        HttpResponse<String> other = session.get(errors(teamA) + "/" + gB1);       // B 의 그룹을 A 로
        HttpResponse<String> missing = session.get(errors(teamA) + "/999999");

        assertThat(other.statusCode()).isEqualTo(404);
        assertThat(map(other).get("error")).isEqualTo("GROUP_NOT_FOUND");
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(other.body()).isEqualTo(missing.body());
    }

    @Test
    void 없는_팀은_404() {
        ApiSession session = ApiSession.login(port, admin);

        HttpResponse<String> list = session.get(errors(999999));
        HttpResponse<String> detail = session.get(errors(999999) + "/" + gA1);

        assertThat(list.statusCode()).isEqualTo(404);
        assertThat(map(list).get("error")).isEqualTo("TEAM_NOT_FOUND");
        assertThat(detail.statusCode()).isEqualTo(404);
        assertThat(map(detail).get("error")).isEqualTo("TEAM_NOT_FOUND");
    }

    // ---- 권한 ----

    @Test
    void 관리자가_아닌_사용자는_그_팀_조장도_403() {
        ApiSession ownerSession = ApiSession.login(port, alice);   // 팀 A 조장

        assertThat(ownerSession.get(errors(teamA)).statusCode()).isEqualTo(403);
        assertThat(ownerSession.get(errors(teamA) + "/" + gA1).statusCode()).isEqualTo(403);
        assertThat(ownerSession.get(errors(teamB)).statusCode()).isEqualTo(403);
    }

    @Test
    void 세션이_없으면_401() throws Exception {
        HttpResponse<String> res = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + errors(teamA))).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(res.statusCode()).isEqualTo(401);
    }

    @Test
    void 팀_주소로는_여전히_멤버만_들어간다() {
        // 관리자도 MembershipGuard 예외가 없다 (D-024): 소속 아닌 팀의 /api/teams/{teamId}/errors 는 403
        HttpResponse<String> res = ApiSession.login(port, admin).get("/api/teams/" + teamA + "/errors");

        assertThat(res.statusCode()).isEqualTo(403);
    }

    // ---- 접근 기록 ----

    private long logCount() {
        return owner.queryForObject("SELECT count(*) FROM admin_access_log", Long.class);
    }

    // 접근 기록은 응답이 나간 뒤(afterCompletion)에 쓰이므로 잠깐 기다린다
    private void waitForLogs(long expected) throws InterruptedException {
        for (int i = 0; i < 40 && logCount() < expected; i++) {
            Thread.sleep(50);
        }
    }

    @Test
    void 조회_한_번마다_접근_기록이_한_줄_남고_에러_내용은_없다() throws Exception {
        ApiSession session = ApiSession.login(port, admin);

        session.get(errors(teamA));
        session.get(errors(teamA) + "/" + gA1);
        waitForLogs(2);

        List<Map<String, Object>> rows = owner.queryForList(
                "SELECT admin_user_id, tenant_id, action FROM admin_access_log ORDER BY id");
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> {
            assertThat(((Number) row.get("admin_user_id")).longValue()).isEqualTo(admin);
            assertThat(((Number) row.get("tenant_id")).longValue()).isEqualTo(teamA);   // 팀 번호가 남는다
        });
        assertThat(rows.get(0).get("action")).isEqualTo("GET /api/admin/teams/{teamId}/errors 200");
        assertThat(rows.get(1).get("action")).isEqualTo("GET /api/admin/teams/{teamId}/errors/{groupId} 200");
        // 에러 내용·AI 설명은 기록에 넣지 않는다
        assertThat(rows.toString()).doesNotContain("boom-A").doesNotContain("가짜 설명");
    }

    // ---- TenantContext ----

    @Test
    void 조회가_끝나면_같은_스레드의_TenantContext_가_비어_있다() {
        controller.list(teamA, null, null, null, null, null, 0, 20);
        assertThat(TenantContext.get()).isEmpty();

        controller.detail(teamA, gA1);
        assertThat(TenantContext.get()).isEmpty();
    }

    @Test
    void 예외가_나도_TenantContext_를_지운다() {
        assertThatThrownBy(() -> controller.detail(teamA, gB1))   // 다른 팀 그룹
                .isInstanceOf(ErrorGroupQueryService.GroupNotFoundException.class);
        assertThat(TenantContext.get()).isEmpty();

        assertThatThrownBy(() -> controller.detail(999999L, gA1))   // 없는 팀
                .isInstanceOf(AdminApiException.class);
        assertThat(TenantContext.get()).isEmpty();
    }

    // ---- 읽기 전용 ----

    @Test
    void 쓰기_경로는_없다() {
        ApiSession session = ApiSession.login(port, admin);
        String list = errors(teamA);
        String one = errors(teamA) + "/" + gA1;

        // GET 만 있는 주소에 다른 방식은 405, 아예 없는 주소(상태·평가)는 404
        assertThat(session.post(list, "{}").statusCode()).isEqualTo(405);
        assertThat(session.patch(one, "{\"status\":\"RESOLVED\"}").statusCode()).isEqualTo(405);
        assertThat(session.delete(one).statusCode()).isEqualTo(405);
        assertThat(session.patch(one + "/status", "{\"status\":\"RESOLVED\"}").statusCode()).isEqualTo(404);
        assertThat(session.put(one + "/feedback", "{\"feedback\":\"UP\"}").statusCode()).isEqualTo(404);

        String status = owner.queryForObject("SELECT status FROM error_group WHERE id = ?", String.class, gA1);
        assertThat(status).isEqualTo("OPEN");   // 아무것도 바뀌지 않았다
    }
}