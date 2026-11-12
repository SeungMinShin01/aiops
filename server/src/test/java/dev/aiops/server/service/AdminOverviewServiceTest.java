package dev.aiops.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

import dev.aiops.server.dto.OverviewRow;
import dev.aiops.server.model.repository.TenantStatsQuery;
import dev.aiops.server.security.TenantContext;
import dev.aiops.server.support.PostgresContainerSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 관리자 전체 현황 (AI-86). 서비스를 직접 부른다. 앱 계정(aiops_app)은 RLS 가 적용되고,
 * 시드는 테이블 소유자(owner)로 넣는다. 팀 A: 이벤트 3 + heartbeat local 1행, 팀 B: 이벤트 1 + heartbeat 없음.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AdminOverviewServiceTest extends PostgresContainerSupport {

    @Autowired AdminOverviewService service;
    @MockitoSpyBean TenantStatsQuery statsQuery;   // 진짜 동작은 그대로, 필요한 테스트에서만 한 팀을 실패시킨다

    private JdbcTemplate owner;
    private long cohort;
    private long teamA;
    private long teamB;

    @BeforeEach
    void seed() {
        owner = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), OWNER_USER, OWNER_PASSWORD));
        owner.execute("TRUNCATE membership, invitation, admin_access_log, app_user, cohort, error_event, error_group, "
                + "app_snapshot, ingest_stat, heartbeat_last, api_key, tenant RESTART IDENTITY CASCADE");
        cohort = owner.queryForObject("INSERT INTO cohort (name) VALUES ('2026-3기') RETURNING id", Long.class);
        teamA = owner.queryForObject("INSERT INTO tenant (name, status, cohort_id) VALUES ('A팀', 'ACTIVE', ?) RETURNING id",
                Long.class, cohort);
        teamB = owner.queryForObject("INSERT INTO tenant (name, status, cohort_id) VALUES ('B팀', 'WAITING_OWNER', ?) RETURNING id",
                Long.class, cohort);

        for (int i = 0; i < 3; i++) {
            event(teamA, "now()");
        }
        event(teamB, "now()");
        owner.update("INSERT INTO heartbeat_last (tenant_id, env, host_hash, last_seen_at) "
                + "VALUES (?, 'local', 'hostA', now() - interval '30 seconds')", teamA);

        long captain = owner.queryForObject("INSERT INTO app_user (github_id, login) VALUES (9601, 'captain') RETURNING id", Long.class);
        owner.update("INSERT INTO membership (user_id, tenant_id, role) VALUES (?, ?, 'OWNER')", captain, teamA);
    }

    // received_at 은 SQL 식으로 넣는다 (예: now(), now() - interval '25 hours')
    private void event(long tenantId, String receivedAtSql) {
        owner.update("INSERT INTO error_event (tenant_id, env, payload, received_at) VALUES (?, 'local', '{}'::jsonb, "
                + receivedAtSql + ")", tenantId);
    }

    private OverviewRow row(List<OverviewRow> rows, long teamId) {
        return rows.stream().filter(r -> r.teamId() == teamId).findFirst().orElseThrow();
    }

    @Test
    void 팀마다_수치가_섞이지_않는다() {
        List<OverviewRow> rows = service.overview(cohort);

        assertThat(rows).hasSize(2);
        OverviewRow a = row(rows, teamA);
        OverviewRow b = row(rows, teamB);
        assertThat(a.errors24h()).isEqualTo(3);
        assertThat(b.errors24h()).isEqualTo(1);
        assertThat(a.lastSeenLocal()).isAfter(Instant.now().minus(Duration.ofMinutes(5)));   // 방금 본 신호
        assertThat(a.lastSeenProd()).isNull();
        assertThat(b.lastSeenLocal()).isNull();   // 팀 B 는 heartbeat 행이 없다 (A 의 행이 섞여 보이면 안 됨)
        assertThat(b.lastSeenProd()).isNull();
    }

    @Test
    void 조장_로그인_기수_이름_팀_상태가_들어간다() {
        List<OverviewRow> rows = service.overview(cohort);

        OverviewRow a = row(rows, teamA);
        assertThat(a.name()).isEqualTo("A팀");
        assertThat(a.cohort()).isEqualTo("2026-3기");
        assertThat(a.status().name()).isEqualTo("ACTIVE");
        assertThat(a.ownerLogin()).isEqualTo("captain");
        assertThat(a.discordStatus().name()).isEqualTo("NONE");
        assertThat(row(rows, teamB).ownerLogin()).isNull();   // 조장이 없는 팀
        assertThat(row(rows, teamB).status().name()).isEqualTo("WAITING_OWNER");
    }

    @Test
    void 팀_번호_순이고_기수를_정하면_그_기수만_생략하면_전체() {
        long otherCohort = owner.queryForObject("INSERT INTO cohort (name) VALUES ('2026-4기') RETURNING id", Long.class);
        long teamC = owner.queryForObject("INSERT INTO tenant (name, status, cohort_id) VALUES ('C팀', 'ACTIVE', ?) RETURNING id",
                Long.class, otherCohort);
        long noCohort = owner.queryForObject("INSERT INTO tenant (name, status) VALUES ('기수없음', 'ACTIVE') RETURNING id", Long.class);

        assertThat(service.overview(cohort)).extracting(OverviewRow::teamId).containsExactly(teamA, teamB);
        assertThat(service.overview(null)).extracting(OverviewRow::teamId).containsExactly(teamA, teamB, teamC, noCohort);
        assertThat(row(service.overview(null), noCohort).cohort()).isNull();
        assertThat(service.overview(99999L)).isEmpty();
    }

    @Test
    void 이십오_시간_전_이벤트는_제외한다() {
        event(teamA, "now() - interval '25 hours'");
        event(teamA, "now() - interval '23 hours'");   // 24시간 안은 포함

        List<OverviewRow> rows = service.overview(cohort);

        assertThat(row(rows, teamA).errors24h()).isEqualTo(4);   // 기존 3 + 23시간 전 1, 25시간 전은 제외
    }

    @Test
    void env별로_마지막_신호_시각을_준다_가장_최근_값() {
        owner.update("INSERT INTO heartbeat_last (tenant_id, env, host_hash, last_seen_at) "
                + "VALUES (?, 'local', 'hostA2', now() - interval '2 hours')", teamA);   // 같은 env 의 오래된 호스트
        owner.update("INSERT INTO heartbeat_last (tenant_id, env, host_hash, last_seen_at) "
                + "VALUES (?, 'prod', 'hostP', now() - interval '10 minutes')", teamA);

        OverviewRow a = row(service.overview(cohort), teamA);

        assertThat(a.lastSeenLocal()).isAfter(Instant.now().minus(Duration.ofMinutes(5)));   // 2시간 전이 아니라 30초 전 값
        assertThat(a.lastSeenProd()).isBetween(Instant.now().minus(Duration.ofMinutes(15)), Instant.now().minus(Duration.ofMinutes(5)));
    }

    @Test
    void 호출이_끝나면_TenantContext는_비어_있다() {
        service.overview(null);

        assertThat(TenantContext.get()).isEmpty();
    }

    @Test
    void 한_팀의_집계가_실패해도_다른_팀은_정상이고_TenantContext는_비어_있다() {
        doAnswer(invocation -> {
            if (TenantContext.get().map(id -> id == teamA).orElse(false)) {
                throw new IllegalStateException("boom");   // 팀 A 의 집계만 실패
            }
            return invocation.callRealMethod();
        }).when(statsQuery).collect();

        List<OverviewRow> rows = service.overview(cohort);

        OverviewRow a = row(rows, teamA);
        assertThat(a.errors24h()).isNull();          // 실패한 팀은 수치만 null
        assertThat(a.lastSeenLocal()).isNull();
        assertThat(a.name()).isEqualTo("A팀");        // 팀 정보와 조장은 그대로
        assertThat(a.ownerLogin()).isEqualTo("captain");
        assertThat(row(rows, teamB).errors24h()).isEqualTo(1);   // 다음 팀은 계속
        assertThat(TenantContext.get()).isEmpty();
    }

    @Test
    void 앱_계정은_RLS를_우회하지_못한다() {
        Boolean bypass = owner.queryForObject("SELECT rolbypassrls FROM pg_roles WHERE rolname = 'aiops_app'", Boolean.class);

        assertThat(bypass).isFalse();
    }
}