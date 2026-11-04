package dev.aiops.server.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aiops.server.model.entity.ErrorEventEntity;
import dev.aiops.server.model.entity.TenantEntity;
import dev.aiops.server.model.repository.ErrorEventRepository;
import dev.aiops.server.model.repository.TenantRepository;
import dev.aiops.server.support.PostgresContainerSupport;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

// AI-61 : run() 을 직접 불러서 확인 (새벽 4시를 기다리지 않음). 묶음 크기를 2로 줄여서 "여러 묶음 반복"도 확인
// 테스트가 끝나면 DB 는 자동 롤백 (진짜 Postgres 사용)
@SpringBootTest(properties = {"aiops.retention.batch-size=2", "aiops.retention.pause-ms=0"})
@Transactional
class RetentionSchedulerTest extends PostgresContainerSupport {

    @Autowired RetentionScheduler scheduler;
    @Autowired ErrorEventRepository events;
    @Autowired TenantRepository tenants;
    @Autowired EntityManager em;

    private Long newTenant() {
        return tenants.save(new TenantEntity("ret-" + UUID.randomUUID().toString().substring(0, 8))).getId();
    }

    // 이벤트를 저장하고 받은 시각을 daysAgo 일 전으로 바꿈
    private Long saveEvent(Long tenantId, String env, int daysAgo) {
        Long id = events.save(new ErrorEventEntity(tenantId, env, "{}", "sample", "abc", "abc123def456")).getId();
        em.createNativeQuery(
                        "update error_event set received_at = now() - (CAST(:d AS integer) * interval '1 day') where id = :id")
                .setParameter("d", daysAgo)
                .setParameter("id", id)
                .executeUpdate();
        return id;
    }

    private boolean exists(Long eventId) {
        return ((Number) em.createNativeQuery("select count(*) from error_event where id = :id")
                .setParameter("id", eventId)
                .getSingleResult()).longValue() == 1;
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

    @Test
    void 그룹과_수집통계는_남는다() {
        Long team = newTenant();
        String fingerprint = (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 64);
        Long groupId = ((Number) em.createNativeQuery(
                        "insert into error_group (tenant_id, fingerprint, exception_type, first_env) "
                                + "values (:t, :f, 'java.lang.RuntimeException', 'local') returning id")
                .setParameter("t", team)
                .setParameter("f", fingerprint)
                .getSingleResult()).longValue();
        em.createNativeQuery(
                        "insert into ingest_stat (tenant_id, env, day, received_count, dropped_count) "
                                + "values (:t, 'local', current_date, 5, 1)")
                .setParameter("t", team)
                .executeUpdate();
        Long oldEvent = saveEvent(team, "local", 10);
        em.createNativeQuery("update error_event set group_id = :g where id = :id")
                .setParameter("g", groupId)
                .setParameter("id", oldEvent)
                .executeUpdate();

        scheduler.run();

        assertThat(exists(oldEvent)).isFalse();   // 원본은 사라짐
        assertThat(((Number) em.createNativeQuery("select count(*) from error_group where id = :g")
                .setParameter("g", groupId)
                .getSingleResult()).longValue()).isEqualTo(1);   // 그룹은 남음
        assertThat(((Number) em.createNativeQuery("select count(*) from ingest_stat where tenant_id = :t")
                .setParameter("t", team)
                .getSingleResult()).longValue()).isEqualTo(1);   // 통계는 남음
    }
}