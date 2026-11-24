package dev.aiops.server.controller;

import dev.aiops.server.security.ApiKeyHasher;
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

/**
 * 초대 수락 (AI-105). 진짜 서버 + TestLogin 세션(POST는 CSRF 헤더까지 실린다).
 * 초대는 owner로 직접 넣는다 (조장 링크 발급은 13단계, 팀원 링크 발급은 AI-89).
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InvitationAcceptTest extends PostgresContainerSupport {

    @Value("${local.server.port}")
    int port;

    private final JsonMapper json = JsonMapper.builder().build();
    private JdbcTemplate owner;
    private TestLogin login;
    private long team;
    private long alice;
    private long bob;
    private long carol;

    @BeforeEach
    void seed() {
        owner = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), OWNER_USER, OWNER_PASSWORD));
        login = new TestLogin(port);
        owner.execute("TRUNCATE membership, invitation, admin_access_log, app_user, cohort, error_event, error_group, "
                + "app_snapshot, ingest_stat, heartbeat_last, api_key, tenant RESTART IDENTITY CASCADE");

        team = owner.queryForObject("INSERT INTO tenant (name, status) VALUES ('새 팀', 'WAITING_OWNER') RETURNING id",
                Long.class);
        alice = user(9601, "alice");
        bob = user(9602, "bob");
        carol = user(9603, "carol");
    }

    private long user(long githubId, String loginName) {
        return owner.queryForObject("INSERT INTO app_user (github_id, login) VALUES (?, ?) RETURNING id",
                Long.class, githubId, loginName);
    }

    /** 초대 1건을 넣고 원문 토큰을 돌려준다 (DB에는 해시만) */
    private String invite(String role, int maxUses, String expiresInterval) {
        String raw = "tok-" + role + "-" + maxUses + "-" + System.nanoTime();
        owner.update("INSERT INTO invitation (tenant_id, token_hash, role, max_uses, expires_at) "
                        + "VALUES (?, ?, ?, ?, now() + CAST(? AS interval))",
                team, ApiKeyHasher.sha256Hex(raw), role, maxUses, expiresInterval);
        return raw;
    }

    private static String accept(String token) {
        return "/api/invitations/" + token + "/accept";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(HttpResponse<String> res) {
        return json.readValue(res.body(), Map.class);
    }

    @Test
    void 세션_없으면_401() {
        assertThat(login.anonymous().post(accept(invite("MEMBER", 10, "7 days"))).statusCode()).isEqualTo(401);
    }

    @Test
    void 없는_토큰은_404() {
        assertThat(login.as(alice).post(accept("no-such-token")).statusCode()).isEqualTo(404);
    }

    @Test
    void 조장_링크_수락하면_OWNER_멤버_팀_ACTIVE_next는_key_guide() {
        String token = invite("OWNER", 1, "7 days");

        HttpResponse<String> res = login.as(alice).post(accept(token));

        assertThat(res.statusCode()).isEqualTo(200);
        Map<String, Object> body = body(res);
        assertThat(((Number) body.get("teamId")).longValue()).isEqualTo(team);
        assertThat(body.get("teamName")).isEqualTo("새 팀");
        assertThat(body.get("role")).isEqualTo("OWNER");
        assertThat(body.get("next")).isEqualTo("key-guide");

        assertThat(owner.queryForObject("SELECT role FROM membership WHERE user_id = ? AND tenant_id = ?",
                String.class, alice, team)).isEqualTo("OWNER");
        assertThat(owner.queryForObject("SELECT status FROM tenant WHERE id = ?", String.class, team))
                .isEqualTo("ACTIVE");
    }

    @Test
    void 같은_조장_링크_두_번째_사용은_410() {
        String token = invite("OWNER", 1, "7 days");
        assertThat(login.as(alice).post(accept(token)).statusCode()).isEqualTo(200);

        assertThat(login.as(bob).post(accept(token)).statusCode()).isEqualTo(410);
        assertThat(owner.queryForObject("SELECT count(*) FROM membership WHERE user_id = ?", Long.class, bob))
                .isZero();
    }

    @Test
    void 만료된_링크는_410() {
        String token = invite("MEMBER", 10, "-1 minutes");

        assertThat(login.as(alice).post(accept(token)).statusCode()).isEqualTo(410);
        assertThat(owner.queryForObject("SELECT count(*) FROM membership", Long.class)).isZero();
    }

    @Test
    void 팀원_링크는_횟수까지만_받고_넘으면_410_next는_errors() {
        String token = invite("MEMBER", 2, "7 days");

        HttpResponse<String> first = login.as(alice).post(accept(token));
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(body(first).get("role")).isEqualTo("MEMBER");
        assertThat(body(first).get("next")).isEqualTo("errors");
        assertThat(login.as(bob).post(accept(token)).statusCode()).isEqualTo(200);

        assertThat(login.as(carol).post(accept(token)).statusCode()).isEqualTo(410);   // 3번째
        assertThat(owner.queryForObject("SELECT used_count FROM invitation", Integer.class)).isEqualTo(2);
        assertThat(owner.queryForObject("SELECT status FROM tenant WHERE id = ?", String.class, team))
                .as("팀원 링크는 팀 상태를 바꾸지 않는다").isEqualTo("WAITING_OWNER");
    }

    @Test
    void 이미_멤버면_중복_생성_없고_횟수도_그대로() {
        String token = invite("MEMBER", 10, "7 days");
        TestLogin.Session session = login.as(alice);
        assertThat(session.post(accept(token)).statusCode()).isEqualTo(200);

        HttpResponse<String> again = session.post(accept(token));

        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(body(again).get("next")).isEqualTo("errors");
        assertThat(owner.queryForObject("SELECT count(*) FROM membership WHERE user_id = ?", Long.class, alice))
                .isEqualTo(1L);
        assertThat(owner.queryForObject("SELECT used_count FROM invitation", Integer.class)).isEqualTo(1);
    }

    @Test
    void 수락한_팀의_API를_바로_쓸_수_있다() {
        String token = invite("MEMBER", 10, "7 days");
        TestLogin.Session session = login.as(alice);
        assertThat(session.get("/api/teams/" + team + "/_test/ping").statusCode()).as("수락 전").isEqualTo(403);

        session.post(accept(token));

        assertThat(session.get("/api/teams/" + team + "/_test/ping").statusCode()).as("수락 후").isEqualTo(200);
    }

    @Test
    void DB에는_토큰_원문이_없다() {
        String token = invite("MEMBER", 10, "7 days");
        login.as(alice).post(accept(token));

        List<Map<String, Object>> rows = owner.queryForList("SELECT * FROM invitation");
        assertThat(rows.toString()).doesNotContain(token);
    }
}
