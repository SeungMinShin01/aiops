package dev.aiops.server.security;

import dev.aiops.server.support.PostgresContainerSupport;
import dev.aiops.server.support.TestLogin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MembershipGuard (AI-80). 진짜 서버 + TestLogin 세션 + 테스트 전용 컨트롤러(_test 경로).
 * 톰캣 스레드 1개 → "요청 뒤 TenantContext가 비었는지"를 같은 스레드로 확인한다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.tomcat.threads.max=1",
        "server.tomcat.threads.min-spare=1"
})
class MembershipGuardTest extends PostgresContainerSupport {

    @Value("${local.server.port}")
    int port;

    private JdbcTemplate owner;
    private TestLogin login;
    private long teamA;
    private long teamB;
    private long member;

    @BeforeEach
    void seed() {
        owner = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), OWNER_USER, OWNER_PASSWORD));
        login = new TestLogin(port);
        owner.execute("TRUNCATE membership, invitation, admin_access_log, app_user, cohort, error_event, error_group, "
                + "app_snapshot, ingest_stat, heartbeat_last, api_key, tenant RESTART IDENTITY CASCADE");

        teamA = owner.queryForObject("INSERT INTO tenant (name) VALUES ('팀 A') RETURNING id", Long.class);
        teamB = owner.queryForObject("INSERT INTO tenant (name) VALUES ('팀 B') RETURNING id", Long.class);
        member = owner.queryForObject("INSERT INTO app_user (github_id, login) VALUES (9101, 'member') RETURNING id",
                Long.class);
        owner.update("INSERT INTO membership (user_id, tenant_id, role) VALUES (?, ?, 'MEMBER')", member, teamA);
    }

    private static String ping(Object team) {
        return "/api/teams/" + team + "/_test/ping";
    }

    @Test
    void 멤버면_200이고_컨트롤러의_TenantContext가_URL의_팀이다() {
        HttpResponse<String> res = login.as(member).get(ping(teamA));

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).isEqualTo(String.valueOf(teamA)); // 컨트롤러가 TenantContext.require()를 돌려준다
    }

    @Test
    void 멤버가_아니면_403() {
        assertThat(login.as(member).get(ping(teamB)).statusCode()).isEqualTo(403);
    }

    @Test
    void 팀_번호가_숫자가_아니면_404() {
        assertThat(login.as(member).get(ping("abc")).statusCode()).isEqualTo(404);
    }

    @Test
    void 정지된_팀이면_멤버라도_403과_reason() {
        owner.update("UPDATE tenant SET status = 'SUSPENDED' WHERE id = ?", teamA);

        HttpResponse<String> res = login.as(member).get(ping(teamA));

        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(res.body()).contains("\"reason\":\"SUSPENDED\"");
    }

    @Test
    void 요청이_끝나면_TenantContext가_비어_있다() {
        TestLogin.Session session = login.as(member);

        assertThat(session.get(ping(teamA)).statusCode()).isEqualTo(200);
        assertThat(session.get("/api/_test/tenant").body()).isEqualTo("none");

        assertThat(session.get(ping(teamB)).statusCode()).isEqualTo(403);
        assertThat(session.get("/api/_test/tenant").body()).isEqualTo("none");
    }

    @Test
    void 맵핑이_없어도_다른_팀이면_403_자기_팀이면_404() {
        TestLogin.Session session = login.as(member);

        assertThat(session.get("/api/teams/" + teamB + "/no-such-path").statusCode()).isEqualTo(403);
        assertThat(session.get("/api/teams/" + teamA + "/no-such-path").statusCode()).isEqualTo(404);
    }

    @Test
    void 세션이_없으면_가드_전에_401() {
        assertThat(login.anonymous().get(ping(teamA)).statusCode()).isEqualTo(401);
    }
}
