package dev.aiops.server.controller;

import dev.aiops.server.support.PostgresContainerSupport;
import dev.aiops.server.support.TestLogin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** GET /api/me (AI-78). TestLogin으로 세션을 만든다 (GitHub OAuth 자체는 수동 확인) */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "aiops.admin.github-ids=9001")
class MeControllerTest extends PostgresContainerSupport {

    @Value("${local.server.port}")
    int port;

    private final JsonMapper json = JsonMapper.builder().build();
    private JdbcTemplate owner;
    private TestLogin login;

    private long teamA;
    private long teamB;
    private long hanwoo;
    private long loner;

    @BeforeEach
    void seed() {
        owner = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), OWNER_USER, OWNER_PASSWORD));
        login = new TestLogin(port);
        owner.execute("TRUNCATE membership, invitation, admin_access_log, app_user, cohort, error_event, error_group, "
                + "app_snapshot, ingest_stat, heartbeat_last, api_key, tenant RESTART IDENTITY CASCADE");

        teamA = owner.queryForObject("INSERT INTO tenant (name) VALUES ('팀 A') RETURNING id", Long.class);
        teamB = owner.queryForObject("INSERT INTO tenant (name) VALUES ('팀 B') RETURNING id", Long.class);
        owner.queryForObject("INSERT INTO tenant (name) VALUES ('팀 C') RETURNING id", Long.class); // 소속 아님

        hanwoo = owner.queryForObject(
                "INSERT INTO app_user (github_id, login, name) VALUES (9001, 'hanwoo', '조현우') RETURNING id", Long.class);
        loner = owner.queryForObject(
                "INSERT INTO app_user (github_id, login) VALUES (9002, 'loner') RETURNING id", Long.class);

        owner.update("INSERT INTO membership (user_id, tenant_id, role) VALUES (?, ?, 'MEMBER')", hanwoo, teamB);
        owner.update("INSERT INTO membership (user_id, tenant_id, role) VALUES (?, ?, 'OWNER')", hanwoo, teamA);
    }

    @Test
    void 세션_없으면_401() {
        HttpResponse<String> res = login.anonymous().get("/api/me");

        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(res.headers().firstValue("location")).as("로그인 페이지 redirect 아님").isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void 내_정보와_소속_팀() {
        HttpResponse<String> res = login.as(hanwoo).get("/api/me");

        assertThat(res.statusCode()).isEqualTo(200);
        Map<String, Object> body = json.readValue(res.body(), Map.class);
        assertThat(((Number) body.get("userId")).longValue()).isEqualTo(hanwoo);
        assertThat(body.get("login")).isEqualTo("hanwoo");
        assertThat(body.get("name")).isEqualTo("조현우");
        assertThat(body.get("isAdmin")).isEqualTo(true);

        List<Map<String, Object>> teams = (List<Map<String, Object>>) body.get("teams");
        assertThat(teams).hasSize(2);
        assertThat(((Number) teams.get(0).get("teamId")).longValue()).isEqualTo(teamA);
        assertThat(teams.get(0).get("name")).isEqualTo("팀 A");
        assertThat(teams.get(0).get("role")).isEqualTo("OWNER");
        assertThat(((Number) teams.get(1).get("teamId")).longValue()).isEqualTo(teamB);
        assertThat(teams.get(1).get("role")).isEqualTo("MEMBER");

        assertThat(res.body()).as("토큰·이메일 없음").doesNotContainIgnoringCase("token").doesNotContain("email");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 소속_없고_관리자_아니면_빈_목록과_false() {
        HttpResponse<String> res = login.as(loner).get("/api/me");

        assertThat(res.statusCode()).isEqualTo(200);
        Map<String, Object> body = json.readValue(res.body(), Map.class);
        assertThat(body.get("isAdmin")).isEqualTo(false);
        assertThat((List<Object>) body.get("teams")).isEmpty();
    }
}
