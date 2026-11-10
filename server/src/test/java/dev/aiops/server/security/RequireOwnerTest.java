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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @RequireOwner (AI-81). 테스트 전용 컨트롤러의 _test/owner-action(@RequireOwner)과 _test/ping(애너테이션 없음)을 쓴다.
 * 변경 요청이라 TestLogin이 CSRF 헤더를 같이 싣는다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RequireOwnerTest extends PostgresContainerSupport {

    @Value("${local.server.port}")
    int port;

    private TestLogin login;
    private long team;
    private long ownerUser;
    private long memberUser;

    @BeforeEach
    void seed() {
        JdbcTemplate owner = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), OWNER_USER, OWNER_PASSWORD));
        login = new TestLogin(port);
        owner.execute("TRUNCATE membership, invitation, admin_access_log, app_user, cohort, error_event, error_group, "
                + "app_snapshot, ingest_stat, heartbeat_last, api_key, tenant RESTART IDENTITY CASCADE");

        team = owner.queryForObject("INSERT INTO tenant (name) VALUES ('팀') RETURNING id", Long.class);
        ownerUser = owner.queryForObject("INSERT INTO app_user (github_id, login) VALUES (9301, 'o') RETURNING id",
                Long.class);
        memberUser = owner.queryForObject("INSERT INTO app_user (github_id, login) VALUES (9302, 'm') RETURNING id",
                Long.class);
        owner.update("INSERT INTO membership (user_id, tenant_id, role) VALUES (?, ?, 'OWNER')", ownerUser, team);
        owner.update("INSERT INTO membership (user_id, tenant_id, role) VALUES (?, ?, 'MEMBER')", memberUser, team);
    }

    private String ownerAction() {
        return "/api/teams/" + team + "/_test/owner-action";
    }

    @Test
    void OWNER는_통과() {
        assertThat(login.as(ownerUser).post(ownerAction()).statusCode()).isEqualTo(201);
    }

    @Test
    void MEMBER는_403() {
        assertThat(login.as(memberUser).post(ownerAction()).statusCode()).isEqualTo(403);
    }

    @Test
    void 애너테이션_없는_메서드는_MEMBER도_200() {
        assertThat(login.as(memberUser).get("/api/teams/" + team + "/_test/ping").statusCode()).isEqualTo(200);
    }
}
