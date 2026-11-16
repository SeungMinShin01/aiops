package dev.aiops.server.controller.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.aiops.server.support.ApiSession;
import dev.aiops.server.support.PostgresContainerSupport;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

/**
 * 기수 생성·목록 (AI-82). 진짜 서버 + 테스트 세션. 관리자 GitHub id 9301, 일반 사용자 9302.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "aiops.admin.github-ids=9301")
class CohortControllerTest extends PostgresContainerSupport {

    private static final String VALID =
            "{\"name\":\"2026-3기\",\"startDate\":\"2026-09-01\",\"endDate\":\"2027-02-28\"}";

    @Value("${local.server.port}")
    int port;

    private final JsonMapper json = JsonMapper.builder().build();
    private JdbcTemplate owner;
    private long admin;
    private long normalUser;

    @BeforeEach
    void seed() {
        owner = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), OWNER_USER, OWNER_PASSWORD));
        owner.execute("TRUNCATE membership, invitation, admin_access_log, app_user, cohort, error_event, error_group, "
                + "app_snapshot, ingest_stat, heartbeat_last, api_key, tenant RESTART IDENTITY CASCADE");
        admin = owner.queryForObject("INSERT INTO app_user (github_id, login) VALUES (9301, 'admin') RETURNING id",
                Long.class);
        normalUser = owner.queryForObject("INSERT INTO app_user (github_id, login) VALUES (9302, 'user') RETURNING id",
                Long.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(HttpResponse<String> res) {
        return json.readValue(res.body(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(HttpResponse<String> res) {
        return json.readValue(res.body(), List.class);
    }

    private long cohortCount() {
        return owner.queryForObject("SELECT count(*) FROM cohort", Long.class);
    }

    @Test
    void 기수를_만들면_201과_id를_돌려주고_DB에_저장된다() {
        HttpResponse<String> res = ApiSession.login(port, admin).post("/api/admin/cohorts", VALID);

        assertThat(res.statusCode()).isEqualTo(201);
        Map<String, Object> body = map(res);
        assertThat(((Number) body.get("cohortId")).longValue()).isPositive();
        assertThat(body.get("name")).isEqualTo("2026-3기");
        assertThat(body.get("startDate")).isEqualTo("2026-09-01");
        assertThat(body.get("endDate")).isEqualTo("2027-02-28");
        assertThat(((Number) body.get("teamCount")).longValue()).isZero();
        assertThat(cohortCount()).isEqualTo(1);
    }

    @Test
    void 같은_이름으로_다시_만들면_409() {
        ApiSession session = ApiSession.login(port, admin);
        assertThat(session.post("/api/admin/cohorts", VALID).statusCode()).isEqualTo(201);

        HttpResponse<String> res = session.post("/api/admin/cohorts", VALID);

        assertThat(res.statusCode()).isEqualTo(409);
        assertThat(map(res)).containsEntry("error", "COHORT_NAME_DUPLICATE").containsKey("message");
        assertThat(cohortCount()).isEqualTo(1);
    }

    @Test
    void 종료일이_시작일보다_빠르면_400() {
        HttpResponse<String> res = ApiSession.login(port, admin).post("/api/admin/cohorts",
                "{\"name\":\"역전\",\"startDate\":\"2027-03-01\",\"endDate\":\"2027-02-01\"}");

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(map(res)).containsEntry("error", "COHORT_DATE_ORDER");
        assertThat(cohortCount()).isZero();
    }

    @Test
    void 이름이_비어_있으면_400() {
        ApiSession session = ApiSession.login(port, admin);

        HttpResponse<String> blank = session.post("/api/admin/cohorts", "{\"name\":\"   \"}");
        HttpResponse<String> missing = session.post("/api/admin/cohorts", "{}");

        assertThat(blank.statusCode()).isEqualTo(400);
        assertThat(map(blank)).containsEntry("error", "COHORT_NAME_REQUIRED");
        assertThat(missing.statusCode()).isEqualTo(400);
        assertThat(cohortCount()).isZero();
    }

    @Test
    void 날짜_형식이_틀리면_400() {
        HttpResponse<String> res = ApiSession.login(port, admin).post("/api/admin/cohorts",
                "{\"name\":\"x\",\"startDate\":\"어제\"}");

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(map(res)).containsEntry("error", "INVALID_REQUEST");
    }

    @Test
    void 목록은_최신_순이고_기수별_팀_수가_들어_있다() {
        long older = owner.queryForObject("INSERT INTO cohort (name) VALUES ('오래된 기수') RETURNING id", Long.class);
        long newer = owner.queryForObject("INSERT INTO cohort (name) VALUES ('새 기수') RETURNING id", Long.class);
        owner.update("INSERT INTO tenant (name, cohort_id) VALUES ('1조', ?)", older);
        owner.update("INSERT INTO tenant (name, cohort_id) VALUES ('2조', ?)", older);
        owner.update("INSERT INTO tenant (name) VALUES ('기수 없는 팀')");   // 어느 기수에도 안 세어짐

        HttpResponse<String> res = ApiSession.login(port, admin).get("/api/admin/cohorts");

        assertThat(res.statusCode()).isEqualTo(200);
        List<Map<String, Object>> rows = list(res);
        assertThat(rows).hasSize(2);
        assertThat(((Number) rows.get(0).get("cohortId")).longValue()).isEqualTo(newer);   // 최신 생성이 먼저
        assertThat(((Number) rows.get(0).get("teamCount")).longValue()).isZero();
        assertThat(((Number) rows.get(1).get("cohortId")).longValue()).isEqualTo(older);
        assertThat(((Number) rows.get(1).get("teamCount")).longValue()).isEqualTo(2);
    }

    @Test
    void 관리자가_아니면_생성도_목록도_403이고_세션이_없으면_401() {
        ApiSession user = ApiSession.login(port, normalUser);

        assertThat(user.post("/api/admin/cohorts", VALID).statusCode()).isEqualTo(403);
        assertThat(user.get("/api/admin/cohorts").statusCode()).isEqualTo(403);
        assertThat(cohortCount()).isZero();
    }

    @Test
    void 기수_생성도_접근_기록에_남는다() {
        ApiSession.login(port, admin).post("/api/admin/cohorts", VALID);

        // 기록은 응답이 나간 직후 저장되므로 잠깐 기다리며 확인
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(owner.queryForList("SELECT action FROM admin_access_log", String.class))
                        .containsExactly("POST /api/admin/cohorts 201"));
    }
}