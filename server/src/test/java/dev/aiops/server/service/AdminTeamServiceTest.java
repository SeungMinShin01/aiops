package dev.aiops.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aiops.server.dto.BulkCreateRequest.TeamInput;
import dev.aiops.server.dto.TeamRow;
import dev.aiops.server.support.PostgresContainerSupport;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * 팀 일괄 생성·목록 (AI-83). 서비스를 직접 부른다.
 * 디스코드 채널 생성은 TeamCreated 이벤트로 넘기므로, "팀마다 한 번 호출"은 이벤트 발행 횟수로 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@RecordApplicationEvents
class AdminTeamServiceTest extends PostgresContainerSupport {

    private static final LocalDate END = LocalDate.of(2027, 2, 20);

    @Autowired AdminTeamService service;
    @Autowired ApplicationEvents events;

    private JdbcTemplate owner;
    private long cohort;
    private long admin;

    @BeforeEach
    void seed() {
        owner = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), OWNER_USER, OWNER_PASSWORD));
        owner.execute("TRUNCATE membership, invitation, admin_access_log, app_user, cohort, error_event, error_group, "
                + "app_snapshot, ingest_stat, heartbeat_last, api_key, tenant RESTART IDENTITY CASCADE");
        cohort = owner.queryForObject("INSERT INTO cohort (name) VALUES ('3기') RETURNING id", Long.class);
        admin = owner.queryForObject("INSERT INTO app_user (github_id, login) VALUES (9801, 'admin') RETURNING id",
                Long.class);
    }

    private List<TeamInput> teams(int count) {
        List<TeamInput> list = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            list.add(new TeamInput("3기-" + i + "조", END));
        }
        return list;
    }

    private long tenantCount() {
        return owner.queryForObject("SELECT count(*) FROM tenant", Long.class);
    }

    private void assertStatus(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(AdminApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getCode()).isEqualTo(code);
        });
    }

    @Test
    void 팀_5개를_만들면_행_5개_상태는_WAITING_OWNER() {
        List<TeamRow> rows = service.bulkCreate(cohort, teams(5), admin);

        assertThat(rows).hasSize(5);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.teamId()).isPositive();
            assertThat(r.status().name()).isEqualTo("WAITING_OWNER");
            assertThat(r.discordStatus().name()).isEqualTo("NONE");
            assertThat(r.ownerLogin()).isNull();
        });
        List<Map<String, Object>> db = owner.queryForList(
                "SELECT name, cohort_id, project_end_date, status, discord_status, notify_local FROM tenant ORDER BY id");
        assertThat(db).hasSize(5);
        assertThat(db.get(0)).containsEntry("name", "3기-1조").containsEntry("status", "WAITING_OWNER")
                .containsEntry("discord_status", "NONE").containsEntry("notify_local", true);
        assertThat(((Number) db.get(0).get("cohort_id")).longValue()).isEqualTo(cohort);
        assertThat(db.get(0).get("project_end_date").toString()).isEqualTo("2027-02-20");
    }

    @Test
    void 디스코드_생성은_팀마다_한_번씩_요청된다() {
        List<TeamRow> rows = service.bulkCreate(cohort, teams(5), admin);

        List<Long> requested = events.stream(TeamCreated.class).map(TeamCreated::tenantId).toList();
        assertThat(requested).containsExactlyInAnyOrderElementsOf(rows.stream().map(TeamRow::teamId).toList());
    }

    @Test
    void 한_팀이_저장에_실패해도_나머지는_만들어진다() {
        List<TeamInput> list = teams(5);
        // 3번째 팀 이름이 100자를 넘으면 그 팀의 INSERT 만 DB 에서 거절된다
        list.set(2, new TeamInput("가".repeat(101), END));

        List<TeamRow> rows = service.bulkCreate(cohort, list, admin);

        assertThat(rows).hasSize(4);
        assertThat(tenantCount()).isEqualTo(4);
        assertThat(events.stream(TeamCreated.class).count()).isEqualTo(4);   // 실패한 팀은 디스코드 요청도 없음
    }

    @Test
    void 요청_안에서_이름이_겹치면_409이고_아무것도_안_만든다() {
        List<TeamInput> list = List.of(new TeamInput("A조", END), new TeamInput("B조", END), new TeamInput(" A조 ", END));

        assertStatus(() -> service.bulkCreate(cohort, list, admin), HttpStatus.CONFLICT, "TEAM_NAME_DUPLICATE");
        assertThat(tenantCount()).isZero();
    }

    @Test
    void 같은_기수에_이미_있는_이름이면_409_다른_기수면_가능() {
        service.bulkCreate(cohort, List.of(new TeamInput("A조", END)), admin);

        assertStatus(() -> service.bulkCreate(cohort, List.of(new TeamInput("A조", END)), admin),
                HttpStatus.CONFLICT, "TEAM_NAME_DUPLICATE");
        long other = owner.queryForObject("INSERT INTO cohort (name) VALUES ('4기') RETURNING id", Long.class);
        assertThat(service.bulkCreate(other, List.of(new TeamInput("A조", END)), admin)).hasSize(1);
        assertThat(tenantCount()).isEqualTo(2);
    }

    @Test
    void 기수가_없으면_404_팀이_비었거나_50개_초과면_400() {
        assertStatus(() -> service.bulkCreate(999L, teams(1), admin), HttpStatus.NOT_FOUND, "COHORT_NOT_FOUND");
        assertStatus(() -> service.bulkCreate(null, teams(1), admin), HttpStatus.NOT_FOUND, "COHORT_NOT_FOUND");
        assertStatus(() -> service.bulkCreate(cohort, List.of(), admin), HttpStatus.BAD_REQUEST, "TEAMS_COUNT_INVALID");
        assertStatus(() -> service.bulkCreate(cohort, null, admin), HttpStatus.BAD_REQUEST, "TEAMS_COUNT_INVALID");
        assertStatus(() -> service.bulkCreate(cohort, teams(51), admin), HttpStatus.BAD_REQUEST, "TEAMS_COUNT_INVALID");
        assertThat(service.bulkCreate(cohort, teams(50), admin)).hasSize(50);   // 50개는 가능
    }

    @Test
    void 팀_이름이_비어_있으면_400() {
        assertStatus(() -> service.bulkCreate(cohort, List.of(new TeamInput("  ", END)), admin),
                HttpStatus.BAD_REQUEST, "TEAM_NAME_REQUIRED");
        assertThat(tenantCount()).isZero();
    }

    @Test
    void 목록은_그_기수의_팀만_보여주고_조장이_있으면_로그인_이름이_들어간다() {
        List<TeamRow> created = service.bulkCreate(cohort, teams(2), admin);
        long otherCohort = owner.queryForObject("INSERT INTO cohort (name) VALUES ('4기') RETURNING id", Long.class);
        service.bulkCreate(otherCohort, List.of(new TeamInput("다른 기수 팀", END)), admin);
        long ownerUser = owner.queryForObject("INSERT INTO app_user (github_id, login) VALUES (9802, 'captain') RETURNING id",
                Long.class);
        long member = owner.queryForObject("INSERT INTO app_user (github_id, login) VALUES (9803, 'member') RETURNING id",
                Long.class);
        long firstTeam = created.get(0).teamId();
        owner.update("INSERT INTO membership (user_id, tenant_id, role) VALUES (?, ?, 'MEMBER')", member, firstTeam);
        owner.update("INSERT INTO membership (user_id, tenant_id, role) VALUES (?, ?, 'OWNER')", ownerUser, firstTeam);

        List<TeamRow> rows = service.listByCohort(cohort);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).teamId()).isEqualTo(firstTeam);
        assertThat(rows.get(0).ownerLogin()).isEqualTo("captain");   // 일반 멤버가 아니라 OWNER
        assertThat(rows.get(0).projectEndDate()).isEqualTo(END);
        assertThat(rows.get(1).ownerLogin()).isNull();
        assertThat(service.listByCohort(otherCohort)).extracting(TeamRow::name).containsExactly("다른 기수 팀");
    }
}