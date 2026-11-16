package dev.aiops.server.controller.admin;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aiops.server.support.ApiSession;
import dev.aiops.server.support.PostgresContainerSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

/**
 * 관리자 키 목록 (AI-101). 관리자는 소속이 아닌 팀의 키 목록을 볼 수 있고 (폐기할 키를 고르려고), 그 밖의 사람은 볼 수 없다.
 * 관리자 admin(github 9901), 팀 A 조장 alice, 팀 B 조장 dave. 키는 원문 없이 목록 한 줄(앞자리)만 나온다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "aiops.admin.github-ids=9901")
class AdminKeyListTest extends PostgresContainerSupport {

    @Value("${local.server.port}")
    int port;

    private final JsonMapper json = JsonMapper.builder().build();
    private JdbcTemplate owner;
    private long teamA;
    private long teamB;
    private long admin;
    private long alice;

    @BeforeEach
    void seed() {
        owner = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), OWNER_USER, OWNER_PASSWORD));
        owner.execute("TRUNCATE membership, invitation, admin_access_log, app_user, cohort, error_event, error_group, "
                + "app_snapshot, ingest_stat, heartbeat_last, api_key, tenant RESTART IDENTITY CASCADE");
        teamA = owner.queryForObject("INSERT INTO tenant (name) VALUES ('팀 A') RETURNING id", Long.class);
        teamB = owner.queryForObject("INSERT INTO tenant (name) VALUES ('팀 B') RETURNING id", Long.class);
        admin = user(9901, "admin");
        alice = user(9902, "alice");
        long dave = user(9904, "dave");
        owner.update("INSERT INTO membership (user_id, tenant_id, role) VALUES (?, ?, 'OWNER')", alice, teamA);
        owner.update("INSERT INTO membership (user_id, tenant_id, role) VALUES (?, ?, 'OWNER')", dave, teamB);
    }

    private long user(long githubId, String login) {
        return owner.queryForObject("INSERT INTO app_user (github_id, login) VALUES (?, ?) RETURNING id",
                Long.class, githubId, login);
    }

    private String adminKeys(long teamId) {
        return "/api/admin/teams/" + teamId + "/keys";
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(HttpResponse<String> res) {
        return json.readValue(res.body(), List.class);
    }

    @Test
    void 관리자는_소속이_아닌_팀의_키_목록을_본다_다른_팀_키는_섞이지_않는다() {
        ApiSession session = ApiSession.login(port, admin);   // 관리자는 어느 팀의 멤버도 아니다
        String local = (String) json.readValue(session.post(adminKeys(teamA), "{\"env\":\"local\"}").body(), Map.class).get("key");
        String prod = (String) json.readValue(session.post(adminKeys(teamA), "{\"env\":\"prod\"}").body(), Map.class).get("key");
        String other = (String) json.readValue(session.post(adminKeys(teamB), "{\"env\":\"local\"}").body(), Map.class).get("key");

        HttpResponse<String> res = session.get(adminKeys(teamA));

        assertThat(res.statusCode()).isEqualTo(200);
        List<Map<String, Object>> rows = list(res);
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(row -> row.get("prefix"))
                .containsExactlyInAnyOrder(local.substring(0, 12), prod.substring(0, 12));
        // 원문·해시는 목록에 없다
        assertThat(res.body()).doesNotContain(local).doesNotContain(prod).doesNotContain(other)
                .doesNotContain("keyHash").doesNotContain("key_hash");
        // 목록에는 폐기 여부·마지막 사용 시각 칸이 있다
        assertThat(rows.get(0)).containsKeys("keyId", "env", "prefix", "createdAt", "revokedAt", "lastUsedAt");
    }

    @Test
    void 폐기한_키는_폐기_시각과_함께_목록에_남는다() {
        ApiSession session = ApiSession.login(port, admin);
        long keyId = ((Number) json.readValue(session.post(adminKeys(teamA), "{\"env\":\"local\"}").body(), Map.class)
                .get("keyId")).longValue();
        session.delete(adminKeys(teamA) + "/" + keyId);

        List<Map<String, Object>> rows = list(session.get(adminKeys(teamA)));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("revokedAt")).isNotNull();
    }

    @Test
    void 없는_팀은_404() {
        assertThat(ApiSession.login(port, admin).get(adminKeys(999999)).statusCode()).isEqualTo(404);
    }

    @Test
    void 관리자가_아니면_그_팀_조장도_403() {
        assertThat(ApiSession.login(port, alice).get(adminKeys(teamA)).statusCode()).isEqualTo(403);
    }

    @Test
    void 세션이_없으면_401() throws Exception {
        HttpResponse<String> res = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + adminKeys(teamA))).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(res.statusCode()).isEqualTo(401);
    }
}