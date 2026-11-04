package dev.aiops.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aiops.server.model.entity.TenantEntity;
import dev.aiops.server.model.repository.TenantRepository;
import dev.aiops.server.support.PostgresContainerSupport;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

// AI-52 : 진짜 Postgres 로 ON CONFLICT upsert 검사 (H2 는 ON CONFLICT·xmax 미지원)
@SpringBootTest
class GroupUpserterTest extends PostgresContainerSupport {

    @Autowired GroupUpserter upserter;
    @Autowired TenantRepository tenants;
    @Autowired EntityManager em;

    // 테스트마다 새 팀 (committed 라서 이름이 겹치지 않게 랜덤)
    private Long newTenant() {
        return tenants.save(new TenantEntity("g-" + UUID.randomUUID().toString().substring(0, 8))).getId();
    }

    // 64자 지문 (테스트마다 새 값)
    private String newFingerprint() {
        return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 64);
    }

    private GroupUpserter.GroupSummary summary() {
        return new GroupUpserter.GroupSummary("java.lang.NullPointerException", "dev.shop.A#a", Instant.now());
    }

    private long countGroups(String fingerprint) {
        return ((Number) em.createNativeQuery(
                        "select count(*) from error_group where fingerprint = :f")
                .setParameter("f", fingerprint)
                .getSingleResult()).longValue();
    }

    private long occurrenceOf(Long tenantId, String fingerprint) {
        return ((Number) em.createNativeQuery(
                        "select occurrence_count from error_group where tenant_id = :t and fingerprint = :f")
                .setParameter("t", tenantId)
                .setParameter("f", fingerprint)
                .getSingleResult()).longValue();
    }

    @Test
    void sameErrorTenTimes() {
        Long tenantId = newTenant();
        String fp = newFingerprint();

        List<GroupUpserter.UpsertResult> results = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            results.add(upserter.upsert(tenantId, "local", fp, summary()));
        }

        assertThat(countGroups(fp)).isEqualTo(1);                 // 그룹은 1개
        assertThat(occurrenceOf(tenantId, fp)).isEqualTo(10);     // 횟수 10
        assertThat(results.get(0).isNew()).isTrue();              // 첫 번째만 새 그룹
        assertThat(results.subList(1, 10)).allMatch(r -> !r.isNew());
    }

    @Test
    void concurrentUpsert() throws Exception {
        Long tenantId = newTenant();
        String fp = newFingerprint();
        int threads = 8;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);   // 전원 준비될 때까지 기다리는 문
        CountDownLatch go = new CountDownLatch(1);            // 동시에 출발시키는 신호
        List<Future<GroupUpserter.UpsertResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<GroupUpserter.UpsertResult> task = () -> {
                ready.countDown();
                go.await();
                return upserter.upsert(tenantId, "local", fp, summary());
            };
            futures.add(pool.submit(task));
        }
        ready.await();
        go.countDown();   // 8개 스레드가 동시에 같은 지문으로 upsert

        int newCount = 0;
        for (Future<GroupUpserter.UpsertResult> future : futures) {
            if (future.get().isNew()) {
                newCount++;
            }
        }
        pool.shutdown();

        assertThat(countGroups(fp)).isEqualTo(1);              // 그룹은 1개
        assertThat(occurrenceOf(tenantId, fp)).isEqualTo(8);   // 횟수 8
        assertThat(newCount).isEqualTo(1);                     // 새 그룹은 정확히 1번
    }

    @Test
    void 다른_팀_같은_지문은_그룹이_2개() {
        Long teamA = newTenant();
        Long teamB = newTenant();
        String fp = newFingerprint();

        upserter.upsert(teamA, "local", fp, summary());
        upserter.upsert(teamB, "local", fp, summary());

        assertThat(countGroups(fp)).isEqualTo(2);
    }
}