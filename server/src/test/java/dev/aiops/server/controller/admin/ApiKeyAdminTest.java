package dev.aiops.server.controller.admin;

import dev.aiops.protocol.ProtocolConstants;
import dev.aiops.server.security.ApiKeyHasher;
import dev.aiops.server.support.ApiSession;
import dev.aiops.server.support.PostgresContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 키 발급·폐기 (AI-88, D-092: 관리자만). 진짜 서버 — 발급한 키로 실제 /v1/events를 보내 본다.
 * 관리자 admin(github 9901), 팀 A 조장 alice·팀원 bob, 팀 B 조장 dave.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "aiops.admin.github-ids=9901")
class ApiKeyAdminTest extends PostgresContainerSupport {

    private static final String EMPTY_BATCH =
            "{\"env\":\"local\",\"app\":{\"name\":\"t\",\"hostHash\":\"h1\"},\"events\":[],\"droppedCount\":0}";

    @Value("${local.server.port}")
    int port;

    private final JsonMapper json = JsonMapper.builder().build();
    private JdbcTemplate owner;
    private long team;
    private long otherTeam;
    private long admin;
    private long alice;
    private long bob;
    private long dave;

    @BeforeEach
    void seed() {
        owner = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), OWNER_USER, OWNER_PASSWORD));
        owner.execute("TRUNCATE membership, invitation, admin_access_log, app_user, cohort, error_event, error_group, "
                + "app_snapshot, ingest_stat, heartbeat_last, api_key, tenant RESTART IDENTITY CASCADE");
        team = owner.queryForObject("INSERT INTO tenant (name) VALUES ('팀 A') RETURNING id", Long.class);
        otherTeam = owner.queryForObject("INSERT INTO tenant (name) VALUES ('팀 B') RETURNING id", Long.class);
        admin = user(9901, "admin");
        alice = user(9902, "alice");
        bob = user(9903, "bob");
        dave = user(9904, "dave");
        owner.update("INSERT INTO membership (user_id, tenant_id, role) VALUES (?, ?, 'OWNER')", alice, team);
        owner.update("INSERT INTO membership (user_id, tenant_id, role) VALUES (?, ?, 'MEMBER')", bob, team);
        owner.update("INSERT INTO membership (user_id, tenant_id, role) VALUES (?, ?, 'OWNER')", dave, otherTeam);
    }

    private long user(long githubId, String login) {
        return owner.queryForObject("INSERT INTO app_user (github_id, login) VALUES (?, ?) RETURNING id",
                Long.class, githubId, login);
    }

    private String adminKeys(long teamId) {
        return "/api/admin/teams/" + teamId + "/keys";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(HttpResponse<String> res) {
        return json.readValue(res.body(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(HttpResponse<String> res) {
        return json.readValue(res.body(), List.class);
    }

    private int ingest(String rawKey) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + ProtocolConstants.PATH_EVENTS))
                        .header("Content-Type", "application/json")
                        .header(ProtocolConstants.HEADER_API_KEY, rawKey)
                        .POST(HttpRequest.BodyPublishers.ofString(EMPTY_BATCH)).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private Map<String, Object> issue(long teamId, String env) {
        HttpResponse<String> res = ApiSession.login(port, admin).post(adminKeys(teamId), "{\"env\":\"" + env + "\"}");
        assertThat(res.statusCode()).isEqualTo(201);
        return map(res);
    }

    // ---- 발급 ----

    @Test
    void 관리자가_발급하면_원문은_응답에만_DB엔_해시와_앞자리() throws Exception {
        Map<String, Object> body = issue(team, "local");

        String raw = (String) body.get("key");
        assertThat(raw).startsWith("aiops_l_").hasSize(40);
        assertThat(body.get("env")).isEqualTo("local");

        Map<String, Object> row = owner.queryForMap("SELECT * FROM api_key WHERE id = ?",
                ((Number) body.get("keyId")).longValue());
        assertThat(row.get("key_hash")).isEqualTo(ApiKeyHasher.sha256Hex(raw));
        assertThat(row.get("key_prefix")).isEqualTo(raw.substring(0, 12));
        assertThat(((Number) row.get("created_by")).longValue()).isEqualTo(admin);
        assertThat(row.toString()).doesNotContain(raw);

        assertThat(ingest(raw)).as("발급한 키로 바로 수집").isEqualTo(202);
    }

    @Test
    void prod_키는_aiops_p_로_시작() {
        assertThat((String) issue(team, "prod").get("key")).startsWith("aiops_p_");
    }

    @Test
    void env가_잘못되면_400_팀이_없으면_404() {
        ApiSession session = ApiSession.login(port, admin);
        assertThat(session.post(adminKeys(team), "{\"env\":\"staging\"}").statusCode()).isEqualTo(400);
        assertThat(session.post(adminKeys(99999), "{\"env\":\"local\"}").statusCode()).isEqualTo(404);
        assertThat(owner.queryForObject("SELECT count(*) FROM api_key", Long.class)).isZero();
    }

    @Test
    void 조장_팀원은_발급_폐기_403() {
        assertThat(ApiSession.login(port, alice).post(adminKeys(team), "{\"env\":\"local\"}").statusCode())
                .as("조장").isEqualTo(403);
        assertThat(ApiSession.login(port, bob).post(adminKeys(team), "{\"env\":\"local\"}").statusCode())
                .as("팀원").isEqualTo(403);
        long keyId = ((Number) issue(team, "local").get("keyId")).longValue();
        assertThat(ApiSession.login(port, alice).delete(adminKeys(team) + "/" + keyId).statusCode()).isEqualTo(403);
    }

    @Test
    void 관리자_호출은_접근_기록에_남고_원문은_기록에_없다() {
        String raw = (String) issue(team, "local").get("key");

        List<Map<String, Object>> logs = owner.queryForList("SELECT * FROM admin_access_log");
        assertThat(logs).isNotEmpty();
        assertThat(logs.toString()).doesNotContain(raw);
    }

    // ---- 폐기 ----

    @Test
    void 폐기_직후_그_키로_수집하면_401() throws Exception {
        Map<String, Object> body = issue(team, "local");
        String raw = (String) body.get("key");
        assertThat(ingest(raw)).isEqualTo(202);   // 키 캐시가 따뜻해진 상태

        HttpResponse<String> res = ApiSession.login(port, admin)
                .delete(adminKeys(team) + "/" + ((Number) body.get("keyId")).longValue());

        assertThat(res.statusCode()).isEqualTo(204);
        assertThat(ingest(raw)).isEqualTo(401);
    }

    @Test
    void 다른_팀_키는_폐기할_수_없다_404() throws Exception {
        Map<String, Object> others = issue(otherTeam, "local");
        long keyId = ((Number) others.get("keyId")).longValue();

        assertThat(ApiSession.login(port, admin).delete(adminKeys(team) + "/" + keyId).statusCode()).isEqualTo(404);
        assertThat(ingest((String) others.get("key"))).as("그대로 살아 있다").isEqualTo(202);
    }

    // ---- 조장·팀원 조회 ----

    @Test
    void 팀원도_키_목록을_보고_원문과_해시는_없다() throws Exception {
        String local = (String) issue(team, "local").get("key");
        Map<String, Object> prodIssued = issue(team, "prod");
        ApiSession.login(port, admin).delete(adminKeys(team) + "/" + ((Number) prodIssued.get("keyId")).longValue());
        ingest(local);   // 마지막 사용 시각이 찍힌다

        HttpResponse<String> res = ApiSession.login(port, bob).get("/api/teams/" + team + "/keys");

        assertThat(res.statusCode()).isEqualTo(200);
        List<Map<String, Object>> rows = list(res);
        assertThat(rows).hasSize(2);
        assertThat(res.body()).doesNotContain(local).doesNotContain((String) prodIssued.get("key"))
                .doesNotContain("keyHash").doesNotContain("key_hash");
        Map<String, Object> localRow = rows.stream().filter(r -> "local".equals(r.get("env"))).findFirst().orElseThrow();
        assertThat(localRow.get("prefix")).isEqualTo(local.substring(0, 12));
        assertThat(localRow.get("lastUsedAt")).isNotNull();
        Map<String, Object> prodRow = rows.stream().filter(r -> "prod".equals(r.get("env"))).findFirst().orElseThrow();
        assertThat(prodRow.get("revokedAt")).isNotNull();
    }

    @Test
    void 다른_팀_사람은_키_목록_403() {
        issue(team, "local");
        assertThat(ApiSession.login(port, dave).get("/api/teams/" + team + "/keys").statusCode()).isEqualTo(403);
    }
}
