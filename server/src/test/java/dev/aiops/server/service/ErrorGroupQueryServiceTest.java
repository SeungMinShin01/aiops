package dev.aiops.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aiops.server.dto.DailyCount;
import dev.aiops.server.dto.ErrorGroupDetail;
import dev.aiops.server.dto.RecentEvent;
import dev.aiops.server.model.entity.Feedback;
import dev.aiops.server.model.entity.GroupStatus;
import dev.aiops.server.security.TenantContext;
import dev.aiops.server.support.ApiSession;
import dev.aiops.server.support.PostgresContainerSupport;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * 에러 상세 (AI-96). 서비스는 직접 부르고(팀은 TenantContext 로 지정), 404 는 진짜 서버로 확인한다.
 * 팀 A: gExplained(설명·평가 있음, 원본 7건), gDaily(일별 집계용), gEmpty(설명·원본 없음) / 팀 B: gB. alice 는 팀 A 멤버.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ErrorGroupQueryServiceTest extends PostgresContainerSupport {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Value("${local.server.port}")
    int port;

    @Autowired
    ErrorGroupQueryService service;

    private final JsonMapper json = JsonMapper.builder().build();
    private JdbcTemplate owner;
    private long teamA;
    private long teamB;
    private long alice;
    private long gExplained;
    private long gDaily;
    private long gEmpty;
    private long gB;
    private LocalDate today;

    @BeforeEach
    void seed() {
        today = LocalDate.now(KST);
        owner = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), OWNER_USER, OWNER_PASSWORD));
        owner.execute("TRUNCATE membership, invitation, admin_access_log, app_user, cohort, error_event, error_group, "
                + "app_snapshot, ingest_stat, heartbeat_last, api_key, tenant RESTART IDENTITY CASCADE");
        teamA = owner.queryForObject("INSERT INTO tenant (name) VALUES ('팀 A') RETURNING id", Long.class);
        teamB = owner.queryForObject("INSERT INTO tenant (name) VALUES ('팀 B') RETURNING id", Long.class);
        alice = owner.queryForObject("INSERT INTO app_user (github_id, login) VALUES (9911, 'alice') RETURNING id",
                Long.class);
        owner.update("INSERT INTO membership (user_id, tenant_id, role) VALUES (?, ?, 'MEMBER')", alice, teamA);

        gExplained = group(teamA, 1, "local", "원인 후보: 가짜 설명", "UP");
        gDaily = group(teamA, 2, "local", null, null);
        gEmpty = group(teamA, 3, "prod", null, null);
        gB = group(teamB, 4, "local", null, null);

        // gExplained: 원본 7건 (m1 이 가장 오래됨 ~ m7 이 가장 최근). 최근 5건 = m7 m6 m5 m4 m3
        for (int i = 1; i <= 7; i++) {
            event(teamA, gExplained, at(today, 0, i), "m" + i);
        }
        // gDaily: 오늘 2건, 어제 1건, 13일 전 1건 (14칸 안), 14일 전 1건 (14칸 밖)
        event(teamA, gDaily, at(today, 0, 30), "d");
        event(teamA, gDaily, at(today, 0, 40), "d");
        event(teamA, gDaily, at(today.minusDays(1), 12, 0), "d");
        event(teamA, gDaily, at(today.minusDays(13), 12, 0), "d");
        event(teamA, gDaily, at(today.minusDays(14), 12, 0), "d");
        event(teamB, gB, at(today, 1, 0), "other-team");
    }

    // 그날 시:분 (한국 시간) → DB 에 넣을 UTC 문자열
    private String at(LocalDate day, int hour, int minute) {
        return ZonedDateTime.of(day, LocalTime.of(hour, minute), KST).toInstant().toString();
    }

    private long group(long tenantId, int n, String env, String explanation, String feedback) {
        return owner.queryForObject("INSERT INTO error_group (tenant_id, fingerprint, exception_type, top_frame, "
                + "first_env, occurrence_count, ai_explanation, ai_explained_at, feedback) "
                + "VALUES (?, ?, 'java.lang.IllegalStateException', 'dev.shop.OrderService#place', ?, 7, "
                + "CAST(? AS text), CASE WHEN CAST(? AS text) IS NULL THEN NULL ELSE now() END, CAST(? AS text)) "
                + "RETURNING id", Long.class, tenantId, String.format("%064d", n), env, explanation, explanation,
                feedback);
    }

    private void event(long tenantId, long groupId, String receivedAt, String message) {
        owner.update("INSERT INTO error_event (tenant_id, env, group_id, payload, received_at, processed) "
                + "VALUES (?, 'local', ?, CAST(? AS jsonb), CAST(? AS timestamptz), true)",
                tenantId, groupId, payload(message), receivedAt);
    }

    private String payload(String message) {
        return "{\"time\":1700000000000,\"level\":\"ERROR\",\"exceptionType\":\"java.lang.IllegalStateException\","
                + "\"message\":\"" + message + "\","
                + "\"stack\":[{\"className\":\"dev.shop.OrderService\",\"methodName\":\"place\","
                + "\"fileName\":\"OrderService.java\",\"lineNumber\":48},"
                + "{\"className\":\"dev.shop.OrderController\",\"methodName\":\"create\","
                + "\"fileName\":\"OrderController.java\",\"lineNumber\":31}],"
                + "\"causes\":[{\"type\":\"java.sql.SQLException\",\"message\":\"db down\"}],"
                + "\"thread\":\"main\",\"phase\":\"RUNTIME\"}";
    }

    // 서비스를 직접 부를 때: 팀을 정하고 → 부르고 → 반드시 지운다 (MembershipGuard 가 하던 일)
    private ErrorGroupDetail detail(long teamId, long groupId) {
        TenantContext.set(teamId);
        try {
            return service.detail(groupId);
        } finally {
            TenantContext.clear();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(HttpResponse<String> res) {
        return json.readValue(res.body(), Map.class);
    }

    @Test
    void 그룹_요약_필드가_채워진다() {
        ErrorGroupDetail detail = detail(teamA, gExplained);

        assertThat(detail.id()).isEqualTo(gExplained);
        assertThat(detail.exceptionType()).isEqualTo("java.lang.IllegalStateException");
        assertThat(detail.location()).isEqualTo("dev.shop.OrderService#place");
        assertThat(detail.env()).isEqualTo("local");
        assertThat(detail.status()).isEqualTo(GroupStatus.OPEN);
        assertThat(detail.count()).isEqualTo(7);
        assertThat(detail.fingerprint()).hasSize(64);
        assertThat(detail.aiExplanation()).isEqualTo("원인 후보: 가짜 설명");
        assertThat(detail.aiExplainedAt()).isNotNull();
        assertThat(detail.feedback()).isEqualTo(Feedback.UP);
    }

    @Test
    void 최근_원본은_설정한_건수만_최신순으로() {
        ErrorGroupDetail detail = detail(teamA, gExplained);

        assertThat(detail.recentEvents()).extracting(RecentEvent::message)
                .containsExactly("m7", "m6", "m5", "m4", "m3");
    }

    @Test
    void 원본에서_메시지_스택_원인_스레드_단계를_꺼낸다() {
        RecentEvent first = detail(teamA, gExplained).recentEvents().get(0);

        assertThat(first.occurredAt()).isEqualTo(Instant.ofEpochMilli(1700000000000L));
        assertThat(first.stack()).containsExactly(
                "dev.shop.OrderService.place(OrderService.java:48)",
                "dev.shop.OrderController.create(OrderController.java:31)");
        assertThat(first.causes()).containsExactly("java.sql.SQLException: db down");
        assertThat(first.thread()).isEqualTo("main");
        assertThat(first.phase()).isEqualTo("RUNTIME");
    }

    @Test
    void 일별_횟수는_14칸이고_원본이_없는_날은_0() {
        List<DailyCount> daily = detail(teamA, gDaily).dailyCounts();

        assertThat(daily).hasSize(14);
        assertThat(daily.get(0).date()).isEqualTo(today.minusDays(13));   // 오래된 날부터
        assertThat(daily.get(13).date()).isEqualTo(today);                // 마지막이 오늘
        assertThat(daily.get(0).count()).isEqualTo(1);                    // 13일 전
        assertThat(daily.get(12).count()).isEqualTo(1);                   // 어제
        assertThat(daily.get(13).count()).isEqualTo(2);                   // 오늘
        assertThat(daily.stream().mapToLong(DailyCount::count).sum()).isEqualTo(4);   // 14일 전 1건은 칸 밖
        assertThat(daily.subList(1, 12)).allMatch(d -> d.count() == 0);
    }

    @Test
    void 설명도_원본도_없는_그룹은_null_과_빈_목록으로_정상_응답() {
        ErrorGroupDetail detail = detail(teamA, gEmpty);

        assertThat(detail.aiExplanation()).isNull();
        assertThat(detail.aiExplainedAt()).isNull();
        assertThat(detail.feedback()).isNull();
        assertThat(detail.recentEvents()).isEmpty();
        assertThat(detail.dailyCounts()).hasSize(14).allMatch(d -> d.count() == 0);
    }

    @Test
    void 원본_보관_일수는_그룹의_env_에_따라_다르다() {
        assertThat(detail(teamA, gExplained).retentionDays()).isEqualTo(3);   // local
        assertThat(detail(teamA, gEmpty).retentionDays()).isEqualTo(14);      // prod
    }

    @Test
    void 다른_팀_그룹은_서비스에서도_없는_것으로_나온다() {
        assertThatThrownBy(() -> detail(teamA, gB))
                .isInstanceOf(ErrorGroupQueryService.GroupNotFoundException.class);
        assertThatThrownBy(() -> detail(teamA, 999999L))
                .isInstanceOf(ErrorGroupQueryService.GroupNotFoundException.class);
    }

    @Test
    void 다른_팀_그룹과_없는_그룹은_HTTP_로_똑같이_404() {
        ApiSession session = ApiSession.login(port, alice);

        HttpResponse<String> other = session.get("/api/teams/" + teamA + "/errors/" + gB);
        HttpResponse<String> missing = session.get("/api/teams/" + teamA + "/errors/999999");

        assertThat(other.statusCode()).isEqualTo(404);   // 403 이 아니다
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(other.body()).isEqualTo(missing.body());   // 존재 여부가 드러나지 않는다
        assertThat(map(other).get("error")).isEqualTo("group_not_found");
    }

    @Test
    void HTTP_응답에_상세_필드가_다_있다() {
        HttpResponse<String> res = ApiSession.login(port, alice).get("/api/teams/" + teamA + "/errors/" + gExplained);

        assertThat(res.statusCode()).isEqualTo(200);
        Map<String, Object> body = map(res);
        assertThat(body.keySet()).containsExactlyInAnyOrder("id", "exceptionType", "location", "env", "status",
                "count", "firstSeen", "lastSeen", "fingerprint", "aiExplanation", "aiExplainedAt", "feedback",
                "recentEvents", "dailyCounts", "retentionDays");
        assertThat((List<?>) body.get("recentEvents")).hasSize(5);
        assertThat((List<?>) body.get("dailyCounts")).hasSize(14);
        assertThat(((Number) body.get("retentionDays")).intValue()).isEqualTo(3);
    }
}