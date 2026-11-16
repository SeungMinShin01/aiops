package dev.aiops.server.controller;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aiops.server.support.ApiSession;
import dev.aiops.server.support.PostgresContainerSupport;
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
 * 에러 그룹 목록 (AI-95). 진짜 서버 + 테스트 세션 + RLS 가 켜진 app 계정.
 * 팀 A: 그룹 g1~g4, 팀 B: 그룹 gB. alice 는 팀 A, bob 은 팀 B 의 멤버.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ErrorGroupControllerTest extends PostgresContainerSupport {

    @Value("${local.server.port}")
    int port;

    private final JsonMapper json = JsonMapper.builder().build();
    private JdbcTemplate owner;
    private long teamA;
    private long teamB;
    private long alice;
    private long bob;
    private long g1;   // local OPEN     횟수 5  11-02
    private long g2;   // local RESOLVED 횟수 9  11-03 (AI 설명 있음)
    private long g3;   // prod  OPEN     횟수 1  11-05
    private long g4;   // local OPEN     횟수 2  11-20
    private long gB;   // 팀 B, local OPEN 횟수 100 11-04

    @BeforeEach
    void seed() {
        owner = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), OWNER_USER, OWNER_PASSWORD));
        owner.execute("TRUNCATE membership, invitation, admin_access_log, app_user, cohort, error_event, error_group, "
                + "app_snapshot, ingest_stat, heartbeat_last, api_key, tenant RESTART IDENTITY CASCADE");
        teamA = owner.queryForObject("INSERT INTO tenant (name) VALUES ('팀 A') RETURNING id", Long.class);
        teamB = owner.queryForObject("INSERT INTO tenant (name) VALUES ('팀 B') RETURNING id", Long.class);
        alice = user(9901, "alice");
        bob = user(9902, "bob");
        owner.update("INSERT INTO membership (user_id, tenant_id, role) VALUES (?, ?, 'MEMBER')", alice, teamA);
        owner.update("INSERT INTO membership (user_id, tenant_id, role) VALUES (?, ?, 'MEMBER')", bob, teamB);

        g1 = group(teamA, 1, "local", "OPEN", 5, "2026-11-02T12:00:00+09:00", null);
        g2 = group(teamA, 2, "local", "RESOLVED", 9, "2026-11-03T12:00:00+09:00", "원인 후보: 비밀 설명 본문");
        g3 = group(teamA, 3, "prod", "OPEN", 1, "2026-11-05T12:00:00+09:00", null);
        g4 = group(teamA, 4, "local", "OPEN", 2, "2026-11-20T12:00:00+09:00", null);
        gB = group(teamB, 5, "local", "OPEN", 100, "2026-11-04T12:00:00+09:00", null);
    }

    private long user(long githubId, String login) {
        return owner.queryForObject("INSERT INTO app_user (github_id, login) VALUES (?, ?) RETURNING id",
                Long.class, githubId, login);
    }

    private long group(long tenantId, int n, String env, String status, int count, String lastSeen,
            String explanation) {
        return owner.queryForObject("INSERT INTO error_group (tenant_id, fingerprint, exception_type, top_frame, "
                + "first_env, first_seen_at, last_seen_at, occurrence_count, status, ai_explanation) "
                + "VALUES (?, ?, 'java.lang.IllegalStateException', 'dev.shop.OrderService#place', ?, "
                + "CAST(? AS timestamptz), CAST(? AS timestamptz), ?, ?, CAST(? AS text)) RETURNING id",
                Long.class, tenantId, String.format("%064d", n), env, lastSeen, lastSeen, count, status, explanation);
    }

    private HttpResponse<String> listAsAlice(String query) {
        return ApiSession.login(port, alice).get("/api/teams/" + teamA + "/errors" + query);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(HttpResponse<String> res) {
        return json.readValue(res.body(), Map.class);
    }

    // 응답 content 의 그룹 id 를 나온 순서대로
    @SuppressWarnings("unchecked")
    private List<Long> ids(HttpResponse<String> res) {
        List<Map<String, Object>> content = (List<Map<String, Object>>) map(res).get("content");
        return content.stream().map(row -> ((Number) row.get("id")).longValue()).toList();
    }

    @Test
    void 조건_없이_보면_내_팀_그룹만_최근순으로_나온다() {
        HttpResponse<String> res = listAsAlice("");

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(ids(res)).containsExactly(g4, g3, g2, g1);
        assertThat(((Number) map(res).get("totalElements")).longValue()).isEqualTo(4);
    }

    @Test
    void env_필터() {
        assertThat(ids(listAsAlice("?env=local"))).containsExactly(g4, g2, g1);
        assertThat(ids(listAsAlice("?env=prod"))).containsExactly(g3);
    }

    @Test
    void 상태_필터() {
        assertThat(ids(listAsAlice("?status=OPEN"))).containsExactly(g4, g3, g1);
        assertThat(ids(listAsAlice("?status=RESOLVED"))).containsExactly(g2);
        assertThat(ids(listAsAlice("?status=IGNORED"))).isEmpty();
    }

    @Test
    void env_와_상태를_같이_걸면_둘_다_만족하는_것만() {
        assertThat(ids(listAsAlice("?env=local&status=OPEN"))).containsExactly(g4, g1);
        assertThat(ids(listAsAlice("?env=prod&status=RESOLVED"))).isEmpty();
    }

    @Test
    void 기간_필터는_날짜만_주면_to_는_그날_끝까지() {
        // g2 는 11-03 낮 12시: to=2026-11-03 에 포함돼야 한다
        assertThat(ids(listAsAlice("?from=2026-11-02&to=2026-11-03"))).containsExactly(g2, g1);
        assertThat(ids(listAsAlice("?from=2026-11-05"))).containsExactly(g4, g3);
        assertThat(ids(listAsAlice("?to=2026-11-02"))).containsExactly(g1);
        assertThat(ids(listAsAlice("?from=2026-11-04&to=2026-11-04"))).isEmpty();
    }

    @Test
    void 세_조건을_모두_조합() {
        assertThat(ids(listAsAlice("?env=local&status=OPEN&from=2026-11-01&to=2026-11-07"))).containsExactly(g1);
    }

    @Test
    void 정렬은_최근순_횟수순_둘다_양방향() {
        assertThat(ids(listAsAlice("?sort=lastSeen,asc"))).containsExactly(g1, g2, g3, g4);
        assertThat(ids(listAsAlice("?sort=count,desc"))).containsExactly(g2, g1, g4, g3);
        assertThat(ids(listAsAlice("?sort=count,asc"))).containsExactly(g3, g4, g1, g2);
    }

    @Test
    void 정렬_허용값_밖은_400() {
        assertThat(listAsAlice("?sort=exceptionType,asc").statusCode()).isEqualTo(400);
        assertThat(listAsAlice("?sort=count,sideways").statusCode()).isEqualTo(400);
        assertThat(listAsAlice("?sort=lastSeen,desc,extra").statusCode()).isEqualTo(400);
        assertThat(listAsAlice("?sort=id;drop").statusCode()).isEqualTo(400);
    }

    @Test
    void 잘못된_값은_400() {
        assertThat(listAsAlice("?env=dev").statusCode()).isEqualTo(400);
        assertThat(listAsAlice("?status=DONE").statusCode()).isEqualTo(400);
        assertThat(listAsAlice("?from=2026-11-07&to=2026-11-01").statusCode()).isEqualTo(400);
        assertThat(listAsAlice("?from=hello").statusCode()).isEqualTo(400);
        assertThat(listAsAlice("?page=-1").statusCode()).isEqualTo(400);
        assertThat(listAsAlice("?size=0").statusCode()).isEqualTo(400);
    }

    @Test
    void 페이지_크기는_100까지_101은_400() {
        assertThat(listAsAlice("?size=100").statusCode()).isEqualTo(200);
        HttpResponse<String> tooBig = listAsAlice("?size=101");
        assertThat(tooBig.statusCode()).isEqualTo(400);
        assertThat(map(tooBig).get("error")).isEqualTo("invalid_request");
    }

    @Test
    void 페이징() {
        HttpResponse<String> res = listAsAlice("?size=3&page=1");

        assertThat(ids(res)).containsExactly(g1);   // 최근순 g4,g3,g2 | g1
        Map<String, Object> body = map(res);
        assertThat(((Number) body.get("totalElements")).longValue()).isEqualTo(4);
        assertThat(((Number) body.get("totalPages")).intValue()).isEqualTo(2);
        assertThat(((Number) body.get("number")).intValue()).isEqualTo(1);
        assertThat(((Number) body.get("size")).intValue()).isEqualTo(3);
    }

    @Test
    void 다른_팀_그룹은_절대_안_보인다() {
        // 기간·정렬을 어떻게 걸어도 팀 B 의 gB 는 팀 A 목록에 없다
        assertThat(ids(listAsAlice(""))).doesNotContain(gB);
        assertThat(ids(listAsAlice("?sort=count,desc"))).doesNotContain(gB);   // gB 는 횟수 100 으로 1등일 것
        assertThat(ids(listAsAlice("?from=2026-11-04&to=2026-11-04"))).isEmpty();

        // bob 은 자기 팀 목록에서 gB 만 보고, 팀 A 주소로는 들어가지 못한다
        ApiSession bobSession = ApiSession.login(port, bob);
        assertThat(ids(bobSession.get("/api/teams/" + teamB + "/errors"))).containsExactly(gB);
        assertThat(bobSession.get("/api/teams/" + teamA + "/errors").statusCode()).isEqualTo(403);
    }

    @Test
    void 목록_한_줄에는_요약_필드만_있고_스택과_AI_설명_본문은_없다() {
        HttpResponse<String> res = listAsAlice("?sort=count,desc");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) map(res).get("content");
        assertThat(content.get(0).keySet()).containsExactlyInAnyOrder("id", "exceptionType", "location", "env",
                "status", "count", "firstSeen", "lastSeen", "hasExplanation");
        assertThat(res.body()).doesNotContain("stack").doesNotContain("payload").doesNotContain("비밀 설명 본문");

        // g2 만 AI 설명이 있다
        assertThat(((Number) content.get(0).get("id")).longValue()).isEqualTo(g2);
        assertThat(content.get(0).get("hasExplanation")).isEqualTo(true);
        assertThat(content.get(1).get("hasExplanation")).isEqualTo(false);
        assertThat(content.get(0).get("location")).isEqualTo("dev.shop.OrderService#place");
    }
}