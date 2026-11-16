package dev.aiops.server.service;

import dev.aiops.server.model.entity.AppUserEntity;
import dev.aiops.server.support.PostgresContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** GitHub 로그인 upsert (AI-78). 진짜 Postgres, 서버는 app 계정 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AppUserServiceTest extends PostgresContainerSupport {

    @Autowired AppUserService appUserService;

    private JdbcTemplate owner;

    @BeforeEach
    void clean() {
        owner = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), OWNER_USER, OWNER_PASSWORD));
        owner.execute("TRUNCATE membership, invitation, admin_access_log, app_user RESTART IDENTITY CASCADE");
    }

    @Test
    void 첫_로그인이면_사용자를_만든다() {
        AppUserEntity user = appUserService.upsertFromGithub(1001L, "hanwoo", "조현우");

        assertThat(user.getId()).isNotNull();
        Map<String, Object> row = owner.queryForMap("SELECT github_id, login, name FROM app_user WHERE id = ?",
                user.getId());
        assertThat(((Number) row.get("github_id")).longValue()).isEqualTo(1001L);
        assertThat(row.get("login")).isEqualTo("hanwoo");
        assertThat(row.get("name")).isEqualTo("조현우");
    }

    @Test
    void 같은_github_id로_두_번이면_행은_1개이고_login이_갱신된다() {
        AppUserEntity first = appUserService.upsertFromGithub(1001L, "old-login", null);
        AppUserEntity second = appUserService.upsertFromGithub(1001L, "new-login", "새 이름");

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(owner.queryForObject("SELECT count(*) FROM app_user", Long.class)).isEqualTo(1L);
        assertThat(owner.queryForObject("SELECT login FROM app_user", String.class)).isEqualTo("new-login");
        assertThat(owner.queryForObject("SELECT name FROM app_user", String.class)).isEqualTo("새 이름");
    }

    @Test
    void github_id가_다르면_다른_사용자다() {
        appUserService.upsertFromGithub(1001L, "same-login", null);
        appUserService.upsertFromGithub(1002L, "other", null);

        assertThat(owner.queryForObject("SELECT count(*) FROM app_user", Long.class)).isEqualTo(2L);
    }

    @Test
    void 로그인해도_팀에_자동_가입되지_않는다() {
        AppUserEntity user = appUserService.upsertFromGithub(1001L, "hanwoo", null);

        assertThat(appUserService.teamsOf(user.getId())).isEmpty();
        assertThat(owner.queryForObject("SELECT count(*) FROM membership", Long.class)).isZero();
    }
}
