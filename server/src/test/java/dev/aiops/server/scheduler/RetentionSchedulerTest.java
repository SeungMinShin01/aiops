package dev.aiops.server.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aiops.server.support.PostgresContainerSupport;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

// AI-61 : run() 을 직접 불러서 확인 (새벽 4시를 기다리지 않음). 묶음 크기를 2로 줄여서 "여러 묶음 반복"도 확인
// AI-16 병합(RLS): 클래스 @Transactional 을 두지 않는다 (삭제 트랜잭션이 테스트 트랜잭션에 합류하면 팀 설정이 안 들어가 0건 삭제).
// 넣기·확인은 owner(RLS 무시)로, 시작할 때 비운다
@SpringBootTest(properties = {"aiops.retention.batch-size=2", "aiops.retention.pause-ms=0"})
class RetentionSchedulerTest extends PostgresContainerSupport {

    @Autowired RetentionScheduler scheduler;

    private JdbcTemplate owner;

    @BeforeEach
    void clean() {
        owner = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), OWNER_USER, OWNER_PASSWORD));
        owner.execute("TRUNCATE error_event, error_group, ingest_stat RESTART IDENTITY CASCADE");
    }

    private Long newTenant() {
        return owner.queryForObject("INSERT INTO tenant (name) VALUES (?) RETURNING id", Long.class,
                "ret-" + UUID.randomUUID().toString().substring(0, 8));
    }

    // 이벤트를 받은 시각 daysAgo 일 전으로 저장
    private Long saveEvent(Long tenantId, String env, int daysAgo) {
        return owner.queryForObject("INSERT INTO error_event (tenant_id, env, payload, received_at) "
                        + "VALUES (?, ?, '{}'::jsonb, now() - (? * interval '1 day')) RETURNING id",
                Long.class, tenantId, env, daysAgo);
    }

    private boolean exists(Long eventId) {
        return owner.queryForObject("select count(*) from error_event where id = ?", Long.class, eventId) == 1L;
    }

    @Test
    void 기준일이_넘은_이벤트만_지운다() {
        Long team = newTenant();
        Long oldLocal1 = saveEvent(team, "local", 4);    // local 3일 초과 → 삭제
        Long oldLocal2 = saveEvent(team, "local", 5);    // 삭제 (2묶음이 필요한 양)
        Long freshLocal = saveEvent(team, "local", 2);   // 3일 이내 → 남음
        Long oldProd = saveEvent(team, "prod", 15);      // prod 14일 초과 → 삭제
        Long freshProd = saveEvent(team, "prod", 10);    // 14일 이내 → 남음 (같은 10일인데 local 이었다면 삭제됐을 것)
        Long prodThreeDays = saveEvent(team, "prod", 4); // prod 는 4일이어도 남음 (local 기준으로 지우면 안 됨)

        scheduler.run();

        assertThat(exists(oldLocal1)).isFalse();
        assertThat(exists(oldLocal2)).isFalse();
        assertThat(exists(oldProd)).isFalse();
        assertThat(exists(freshLocal)).isTrue();
        assertThat(exists(freshProd)).isTrue();
        assertThat(exists(prodThreeDays)).isTrue();
    }

    @Test
    void 묶음보다_많아도_전부_지운다() {
        Long team = newTenant();
        // 묶음 크기가 2인데 오래된 이벤트 5개 → 2 + 2 + 1 로 나눠서 반복 삭제되어야 함
        Long[] ids = new Long[5];
        for (int i = 0; i < 5; i++) {
            ids[i] = saveEvent(team, "local", 10);
        }

        scheduler.run();

        for (Long id : ids) {
            assertThat(exists(id)).isFalse();
        }
    }

    // AI-73 : RLS 위에서 팀을 돌며 지우므로 여러 팀의 오래된 이벤트가 모두 지워진다
    @Test
    void 여러_팀의_오래된_이벤트가_모두_지워진다() {
        Long teamA = newTenant();
        Long teamB = newTenant();
        Long oldA = saveEvent(teamA, "local", 10);
        Long oldB = saveEvent(teamB, "local", 10);
        Long freshB = saveEvent(teamB, "local", 1);

        scheduler.run();

        assertThat(exists(oldA)).isFalse();
        assertThat(exists(oldB)).isFalse();
        assertThat(exists(freshB)).isTrue();
    }

    @Test
    void 그룹과_수집통계는_남는다() {
        Long team = newTenant();
        String fingerprint = (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 64);
        Long groupId = owner.queryForObject("insert into error_group (tenant_id, fingerprint, exception_type, first_env) "
                + "values (?, ?, 'java.lang.RuntimeException', 'local') returning id", Long.class, team, fingerprint);
        owner.update("insert into ingest_stat (tenant_id, env, day, received_count, dropped_count) "
                + "values (?, 'local', current_date, 5, 1)", team);
        Long oldEvent = saveEvent(team, "local", 10);
        owner.update("update error_event set group_id = ? where id = ?", groupId, oldEvent);

        scheduler.run();

        assertThat(exists(oldEvent)).isFalse();   // 원본은 사라짐
        assertThat(owner.queryForObject("select count(*) from error_group where id = ?", Long.class, groupId))
                .isEqualTo(1L);   // 그룹은 남음
        assertThat(owner.queryForObject("select count(*) from ingest_stat where tenant_id = ?", Long.class, team))
                .isEqualTo(1L);   // 통계는 남음
    }
}
