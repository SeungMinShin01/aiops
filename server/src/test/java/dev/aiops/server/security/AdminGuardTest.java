package dev.aiops.server.security;

import dev.aiops.server.support.PostgresContainerSupport;
import dev.aiops.server.support.TestLogin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** AdminGuard (AI-81). 관리자 = 환경변수 GitHub id 목록 (여기선 9201) */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "aiops.admin.github-ids=9201")
class AdminGuardTest extends PostgresContainerSupport {

    @Value("${local.server.port}")
    int port;

    private TestLogin login;
    private long adminUser;
    private long ownerOfTeam;

    @BeforeEach
    void seed() {
        JdbcTemplate owner = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), OWNER_USER, OWNER_PASSWORD));
        login = new TestLogin(port);
        owner.execute("TRUNCATE membership, invitation, admin_access_log, app_user, cohort, error_event, error_group, "
                + "app_snapshot, ingest_stat, heartbeat_last, api_key, tenant RESTART IDENTITY CASCADE");

        long team = owner.queryForObject("INSERT INTO tenant (name) VALUES ('팀') RETURNING id", Long.class);
        adminUser = owner.queryForObject("INSERT INTO app_user (github_id, login) VALUES (9201, 'admin') RETURNING id",
                Long.class);
        ownerOfTeam = owner.queryForObject("INSERT INTO app_user (github_id, login) VALUES (9202, 'o') RETURNING id",
                Long.class);
        owner.update("INSERT INTO membership (user_id, tenant_id, role) VALUES (?, ?, 'OWNER')", ownerOfTeam, team);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 목록에_있는_github_id는_200() {
        assertThat(login.as(adminUser).get("/api/admin/_test/ping").statusCode()).isEqualTo(200);
    }

    @Test
    void 목록에_없으면_조장이어도_403() {
        assertThat(login.as(ownerOfTeam).get("/api/admin/_test/ping").statusCode()).isEqualTo(403);
        assertThat(login.as(ownerOfTeam).get("/api/admin/no-such-path").statusCode()).as("맵핑이 없어도 막는다")
                .isEqualTo(403);
    }

    @Test
    void 세션_없으면_401() {
        assertThat(login.anonymous().get("/api/admin/_test/ping").statusCode()).isEqualTo(401);
    }

    @Test
    void 목록이_비어_있으면_전부_403() throws Exception {
        AdminGuard guard = new AdminGuard(new AdminProperties(Set.of()));
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new AppUserPrincipal(1L, 9201L, "admin"), null, List.of()));
        MockHttpServletResponse res = new MockHttpServletResponse();

        boolean passed = guard.preHandle(new MockHttpServletRequest(), res, new Object());

        assertThat(passed).isFalse();
        assertThat(res.getStatus()).isEqualTo(403);
    }
}
