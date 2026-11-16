package dev.aiops.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import dev.aiops.server.notify.Notifier;
import dev.aiops.server.support.ApiSession;
import dev.aiops.server.support.PostgresContainerSupport;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.mockito.Mockito;
import tools.jackson.databind.json.JsonMapper;

/**
 * 에러 그룹 상태 변경·AI 설명 평가·재발 시 다시 열림 (AI-97).
 * 팀 A: gOpen(설명 있음, 횟수 7), gNoExplain(설명 없음) / 팀 B: gB. alice 는 팀 A 멤버.
 * 알림은 가짜 Notifier 로 "불렀는지"만 본다 (디스코드로 실제 전송하지 않는다).
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ErrorGroupStatusTest extends PostgresContainerSupport {

    @Value("${local.server.port}")
    int port;

    @Autowired
    GroupingService groupingService;

    @MockitoBean
    Notifier notifier;

    private final JsonMapper json = JsonMapper.builder().build();
    private JdbcTemplate owner;
    private long teamA;
    private long teamB;
    private long alice;
    private long gOpen;
    private long gNoExplain;
    private long gB;

    @BeforeEach
    void seed() {
        Mockito.reset(notifier);
        owner = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), OWNER_USER, OWNER_PASSWORD));
        owner.execute("TRUNCATE membership, invitation, admin_access_log, app_user, cohort, error_event, error_group, "
                + "app_snapshot, ingest_stat, heartbeat_last, api_key, tenant RESTART IDENTITY CASCADE");
        teamA = owner.queryForObject("INSERT INTO tenant (name) VALUES ('팀 A') RETURNING id", Long.class);
        teamB = owner.queryForObject("INSERT INTO tenant (name) VALUES ('팀 B') RETURNING id", Long.class);
        alice = owner.queryForObject("INSERT INTO app_user (github_id, login) VALUES (9921, 'alice') RETURNING id",
                Long.class);
        owner.update("INSERT INTO membership (user_id, tenant_id, role) VALUES (?, ?, 'MEMBER')", alice, teamA);

        gOpen = group(teamA, 1, "원인 후보: 가짜 설명");
        gNoExplain = group(teamA, 2, null);
        gB = group(teamB, 3, "원인 후보: 다른 팀 설명");
    }

    private long group(long tenantId, int n, String explanation) {
        return owner.queryForObject("INSERT INTO error_group (tenant_id, fingerprint, exception_type, top_frame, "
                + "first_env, occurrence_count, ai_explanation, ai_explained_at) "
                + "VALUES (?, ?, 'java.lang.IllegalStateException', 'dev.shop.OrderService#place', 'local', 7, "
                + "CAST(? AS text), CASE WHEN CAST(? AS text) IS NULL THEN NULL ELSE now() END) RETURNING id",
                Long.class, tenantId, String.format("%064d", n), explanation, explanation);
    }

    private String dbText(String column, long groupId) {
        return owner.queryForObject("SELECT " + column + " FROM error_group WHERE id = ?", String.class, groupId);
    }

    private long dbCount(long groupId) {
        return owner.queryForObject("SELECT occurrence_count FROM error_group WHERE id = ?", Long.class, groupId);
    }

    private String url(long teamId, long groupId, String tail) {
        return "/api/teams/" + teamId + "/errors/" + groupId + "/" + tail;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(HttpResponse<String> res) {
        return json.readValue(res.body(), Map.class);
    }

    // ---- 상태 변경 ----

    @Test
    void 상태를_바꾸면_그_칸만_바뀌고_횟수는_그대로() {
        HttpResponse<String> res = ApiSession.login(port, alice)
                .patch(url(teamA, gOpen, "status"), "{\"status\":\"RESOLVED\"}");

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(map(res).get("status")).isEqualTo("RESOLVED");
        assertThat(dbText("status", gOpen)).isEqualTo("RESOLVED");
        assertThat(dbCount(gOpen)).isEqualTo(7);   // 다른 칸을 옛 값으로 덮지 않는다
    }

    @Test
    void 허용값_밖_상태와_본문_없음은_400() {
        ApiSession session = ApiSession.login(port, alice);

        HttpResponse<String> wrong = session.patch(url(teamA, gOpen, "status"), "{\"status\":\"DONE\"}");
        HttpResponse<String> missing = session.patch(url(teamA, gOpen, "status"), "{}");
        HttpResponse<String> broken = session.patch(url(teamA, gOpen, "status"), "여기는 JSON 이 아니다");

        assertThat(wrong.statusCode()).isEqualTo(400);
        assertThat(map(wrong).get("error")).isEqualTo("invalid_request");
        assertThat(missing.statusCode()).isEqualTo(400);
        assertThat(broken.statusCode()).isEqualTo(400);
        assertThat(dbText("status", gOpen)).isEqualTo("OPEN");
    }

    @Test
    void 다른_팀_그룹과_없는_그룹의_상태는_바꿀_수_없고_404() {
        ApiSession session = ApiSession.login(port, alice);

        HttpResponse<String> other = session.patch(url(teamA, gB, "status"), "{\"status\":\"RESOLVED\"}");
        HttpResponse<String> missing = session.patch(url(teamA, 999999, "status"), "{\"status\":\"RESOLVED\"}");

        assertThat(other.statusCode()).isEqualTo(404);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(other.body()).isEqualTo(missing.body());
        assertThat(dbText("status", gB)).isEqualTo("OPEN");   // 다른 팀 그룹은 그대로
    }

    // ---- AI 설명 평가 ----

    @Test
    void 평가를_남기고_같은_값을_다시_보내도_같은_결과() {
        ApiSession session = ApiSession.login(port, alice);

        HttpResponse<String> first = session.put(url(teamA, gOpen, "feedback"), "{\"feedback\":\"UP\"}");
        HttpResponse<String> again = session.put(url(teamA, gOpen, "feedback"), "{\"feedback\":\"UP\"}");
        HttpResponse<String> changed = session.put(url(teamA, gOpen, "feedback"), "{\"feedback\":\"DOWN\"}");

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(map(first).get("feedback")).isEqualTo("UP");
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(changed.statusCode()).isEqualTo(200);
        assertThat(dbText("feedback", gOpen)).isEqualTo("DOWN");
        assertThat(dbCount(gOpen)).isEqualTo(7);
    }

    @Test
    void 설명이_없는_그룹에는_평가할_수_없고_409() {
        HttpResponse<String> res = ApiSession.login(port, alice)
                .put(url(teamA, gNoExplain, "feedback"), "{\"feedback\":\"UP\"}");

        assertThat(res.statusCode()).isEqualTo(409);
        assertThat(map(res).get("error")).isEqualTo("no_explanation");
        assertThat(dbText("feedback", gNoExplain)).isNull();
    }

    @Test
    void 평가의_다른_팀_그룹은_404_허용값_밖은_400() {
        ApiSession session = ApiSession.login(port, alice);

        assertThat(session.put(url(teamA, gB, "feedback"), "{\"feedback\":\"UP\"}").statusCode()).isEqualTo(404);
        assertThat(session.put(url(teamA, 999999, "feedback"), "{\"feedback\":\"UP\"}").statusCode()).isEqualTo(404);
        assertThat(session.put(url(teamA, gOpen, "feedback"), "{\"feedback\":\"SOSO\"}").statusCode()).isEqualTo(400);
        assertThat(dbText("feedback", gB)).isNull();
    }

    // ---- 재발: 처리기를 직접 돌려서 (동기) ----

    private String payload(int line) {
        return "{\"time\":1700000000000,\"level\":\"ERROR\","
                + "\"exceptionType\":\"java.lang.NullPointerException\",\"message\":\"boom\","
                + "\"stack\":[{\"className\":\"dev.shop.OrderService\",\"methodName\":\"place\","
                + "\"fileName\":\"OrderService.java\",\"lineNumber\":" + line + "}],"
                + "\"causes\":[],\"thread\":\"main\"}";
    }

    private long saveEvent(long tenantId, int line) {
        return owner.queryForObject("INSERT INTO error_event (tenant_id, env, payload, app_name, app_commit, host_hash) "
                + "VALUES (?, 'local', CAST(? AS jsonb), 'sample', 'abc', 'abc123def456') RETURNING id",
                Long.class, tenantId, payload(line));
    }

    private long onlyGroupId(long tenantId) {
        return owner.queryForObject("SELECT id FROM error_group WHERE tenant_id = ? AND id NOT IN (?, ?, ?)",
                Long.class, tenantId, gOpen, gNoExplain, gB);
    }

    @Test
    void 새_그룹은_알리고_같은_그룹_재발은_알리지_않는다() {
        ErrorProcessor processor = new ErrorProcessor(groupingService, null, notifier);
        long first = saveEvent(teamA, 10);
        long second = saveEvent(teamA, 99);   // 줄 번호만 다름 = 같은 그룹

        processor.process(teamA, List.of(first));
        long groupId = onlyGroupId(teamA);
        processor.process(teamA, List.of(second));

        verify(notifier, times(1)).notifyNewGroup(teamA, groupId);
        verify(notifier, never()).notifyReopenedGroup(Mockito.anyLong(), Mockito.anyLong());
        assertThat(dbCount(groupId)).isEqualTo(2);
        assertThat(dbText("status", groupId)).isEqualTo("OPEN");
    }

    @Test
    void 해결로_표시한_그룹이_다시_발생하면_OPEN_으로_돌아오고_재발_알림() {
        ErrorProcessor processor = new ErrorProcessor(groupingService, null, notifier);
        processor.process(teamA, List.of(saveEvent(teamA, 10)));
        long groupId = onlyGroupId(teamA);
        owner.update("UPDATE error_group SET status = 'RESOLVED' WHERE id = ?", groupId);

        processor.process(teamA, List.of(saveEvent(teamA, 99)));

        assertThat(dbText("status", groupId)).isEqualTo("OPEN");
        assertThat(dbCount(groupId)).isEqualTo(2);
        verify(notifier, times(1)).notifyReopenedGroup(teamA, groupId);
        verify(notifier, times(1)).notifyNewGroup(teamA, groupId);   // 처음 만들 때 한 번뿐
    }

    @Test
    void 무시한_그룹은_재발해도_상태가_그대로이고_다시_열림_알림도_없다() {
        ErrorProcessor processor = new ErrorProcessor(groupingService, null, notifier);
        processor.process(teamA, List.of(saveEvent(teamA, 10)));
        long groupId = onlyGroupId(teamA);
        owner.update("UPDATE error_group SET status = 'IGNORED' WHERE id = ?", groupId);
        Mockito.reset(notifier);

        processor.process(teamA, List.of(saveEvent(teamA, 99)));

        assertThat(dbText("status", groupId)).isEqualTo("IGNORED");
        assertThat(dbCount(groupId)).isEqualTo(2);   // 횟수는 계속 올라간다
        verify(notifier, never()).notifyReopenedGroup(Mockito.anyLong(), Mockito.anyLong());
        verify(notifier, never()).notifyNewGroup(Mockito.anyLong(), Mockito.anyLong());
    }
}