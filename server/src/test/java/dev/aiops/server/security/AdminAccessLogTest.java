package dev.aiops.server.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import dev.aiops.server.support.PostgresContainerSupport;
import dev.aiops.server.support.TestLogin;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 접근 기록 (AI-87). 관리자 GitHub id 9201, 조장 9202.
 * 기록은 응답이 나간 뒤(afterCompletion)에 저장되므로 "기록이 생겼는지"는 잠깐 기다리며 확인한다 (Awaitility).
 * 기록이 "없어야 하는" 경우는 인터셉터가 아예 안 불리므로 바로 확인한다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "aiops.admin.github-ids=9201")
@Import(AdminAccessLogTest.TestEndpoints.class)
class AdminAccessLogTest extends PostgresContainerSupport {

    static final String SECRET = "SECRET-TOKEN-ABC123";

    @Value("${local.server.port}")
    int port;

    private TestLogin login;
    private JdbcTemplate owner;
    private long team;
    private long adminUser;
    private long ownerOfTeam;

    // AI-87 : 테스트에서만 쓰는 관리자 경로 3개 (실제 API 는 다른 작업에서 만든다)
    @RestController
    static class TestEndpoints {
        // 경로 변수 teamId 가 있는 경로 → tenant_id 가 채워지는지 확인용
        @GetMapping("/api/admin/_test/teams/{teamId}/log")
        String teamPath(@PathVariable long teamId) {
            return "ok";
        }

        // 일부러 처리 못 한 예외 → 500 으로 끝나도 기록이 남는지 확인용
        @GetMapping("/api/admin/_test/boom")
        String boom() {
            throw new IllegalStateException("boom");
        }

        // 응답에 비밀값이 있는 경로 → 기록에 그 값이 없는지 확인용 (초대 토큰 원문 흉내)
        @GetMapping("/api/admin/_test/secret")
        String secret() {
            return SECRET;
        }
    }

    @BeforeEach
    void seed() {
        owner = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), OWNER_USER, OWNER_PASSWORD));
        login = new TestLogin(port);
        owner.execute("TRUNCATE membership, invitation, admin_access_log, app_user, cohort, error_event, error_group, "
                + "app_snapshot, ingest_stat, heartbeat_last, api_key, tenant RESTART IDENTITY CASCADE");

        team = owner.queryForObject("INSERT INTO tenant (name) VALUES ('팀') RETURNING id", Long.class);
        adminUser = owner.queryForObject("INSERT INTO app_user (github_id, login) VALUES (9201, 'admin') RETURNING id",
                Long.class);
        ownerOfTeam = owner.queryForObject("INSERT INTO app_user (github_id, login) VALUES (9202, 'o') RETURNING id",
                Long.class);
        owner.update("INSERT INTO membership (user_id, tenant_id, role) VALUES (?, ?, 'OWNER')", ownerOfTeam, team);
    }

    private List<Map<String, Object>> logs() {
        return owner.queryForList("SELECT admin_user_id, tenant_id, action FROM admin_access_log ORDER BY id");
    }

    private void awaitRows(int count) {
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(logs()).hasSize(count));
    }

    @Test
    void 관리자_호출_3번이면_3행이고_action에_메서드_패턴_상태가_들어간다() {
        TestLogin.Session session = login.as(adminUser);
        session.get("/api/admin/_test/ping");
        session.get("/api/admin/_test/ping");
        session.get("/api/admin/_test/ping");

        awaitRows(3);
        Map<String, Object> row = logs().get(0);
        assertThat(row.get("action")).isEqualTo("GET /api/admin/_test/ping 200");
        assertThat(((Number) row.get("admin_user_id")).longValue()).isEqualTo(adminUser);
        assertThat(row.get("tenant_id")).isNull();   // 경로에 teamId 가 없으면 null
    }

    @Test
    void 경로의_teamId가_tenant_id에_채워지고_action에는_패턴이_기록된다() {
        login.as(adminUser).get("/api/admin/_test/teams/" + team + "/log");

        awaitRows(1);
        Map<String, Object> row = logs().get(0);
        assertThat(((Number) row.get("tenant_id")).longValue()).isEqualTo(team);
        assertThat(row.get("action")).isEqualTo("GET /api/admin/_test/teams/{teamId}/log 200");
    }

    @Test
    void 관리자가_아니어서_403이면_기록이_없고_세션이_없어도_없다() {
        assertThat(login.as(ownerOfTeam).get("/api/admin/_test/ping").statusCode()).isEqualTo(403);
        assertThat(login.anonymous().get("/api/admin/_test/ping").statusCode()).isEqualTo(401);

        assertThat(logs()).isEmpty();
    }

    @Test
    void 본_요청이_500으로_실패해도_기록은_1행_남는다() {
        login.as(adminUser).get("/api/admin/_test/boom");

        awaitRows(1);
        assertThat((String) logs().get(0).get("action")).isEqualTo("GET /api/admin/_test/boom 500");
    }

    @Test
    void 응답에_있는_비밀값은_기록에_없다() {
        HttpResponse<String> res = login.as(adminUser).get("/api/admin/_test/secret");
        assertThat(res.body()).contains(SECRET);   // 응답에는 있다

        awaitRows(1);
        assertThat(logs().toString()).doesNotContain(SECRET);   // 기록에는 없다
    }

    @Test
    void 팀_API_호출은_기록되지_않는다() {
        HttpResponse<String> res = login.as(ownerOfTeam).get("/api/teams/" + team + "/_test/ping");

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(logs()).isEmpty();
    }

    @Test
    void 조회_API는_최근_기록을_돌려주고_이_조회도_기록된다() {
        TestLogin.Session session = login.as(adminUser);
        session.get("/api/admin/_test/ping");
        awaitRows(1);

        HttpResponse<String> res = session.get("/api/admin/access-log?adminUserId=" + adminUser);

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("GET /api/admin/_test/ping 200");
        awaitRows(2);   // 조회 호출 자신도 1행
        assertThat((String) logs().get(1).get("action")).isEqualTo("GET /api/admin/access-log 200");
    }

    @Test
    void 서버_계정은_기록을_추가하고_읽을_수_있지만_수정_삭제는_못_한다() {
        JdbcTemplate app = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), "aiops_app", "app_test"));

        app.update("INSERT INTO admin_access_log (admin_user_id, action) VALUES (?, 'x')", adminUser);
        assertThat(app.queryForObject("SELECT count(*) FROM admin_access_log", Long.class)).isEqualTo(1L);

        assertThatThrownBy(() -> app.update("UPDATE admin_access_log SET action = 'y'"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> app.update("DELETE FROM admin_access_log"))
                .isInstanceOf(DataAccessException.class);
    }
}