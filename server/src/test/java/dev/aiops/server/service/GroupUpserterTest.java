package dev.aiops.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aiops.server.security.TenantContext;
import dev.aiops.server.support.PostgresContainerSupport;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

// AI-52 : 진짜 Postgres 로 ON CONFLICT upsert 검사 (H2 는 ON CONFLICT·xmax 미지원)
// AI-16 병합(RLS): 실제 서버에서 upsert 는 처리 스레드(ErrorProcessor 가 팀을 set 한 상태)에서만 불린다.
// 테스트도 같은 조건으로: 부르는 스레드마다 TenantContext 를 set/clear. 확인은 owner(RLS 무시)로
@SpringBootTest
class GroupUpserterTest extends PostgresContainerSupport {

    @Autowired GroupUpserter upserter;

    private JdbcTemplate owner;

    @BeforeEach
    void setUp() {
        owner = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), OWNER_USER, OWNER_PASSWORD));
    }

    // 테스트마다 새 팀 (committed 라서 이름이 겹치지 않게 랜덤)
    private Long newTenant() {
        return owner.queryForObject("INSERT INTO tenant (name) VALUES (?) RETURNING id", Long.class,
                "g-" + UUID.randomUUID().toString().substring(0, 8));
    }

    // 처리 스레드처럼: 이 팀으로 set → 일 → clear
    private static <T> T asTenant(Long tenantId, Supplier<T> work) {
        TenantContext.set(tenantId);
        try {
            return work.get();
        } finally {
            TenantContext.clear();
        }
    }

    // 64자 지문 (테스트마다 새 값)
    private String newFingerprint() {
        return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 64);
    }

    private GroupUpserter.GroupSummary summary() {
        return new GroupUpserter.GroupSummary("java.lang.NullPointerException", "dev.shop.A#a", Instant.now());
    }

    private long countGroups(String fingerprint) {
        return owner.queryForObject("select count(*) from error_group where fingerprint = ?", Long.class, fingerprint);
    }

    private long occurrenceOf(Long tenantId, String fingerprint) {
        return owner.queryForObject("select occurrence_count from error_group where tenant_id = ? and fingerprint = ?",
                Long.class, tenantId, fingerprint);
    }

    @Test
    void sameErrorTenTimes() {
        Long tenantId = newTenant();
        String fp = newFingerprint();

        List<GroupUpserter.UpsertResult> results = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            results.add(asTenant(tenantId, () -> upserter.upsert(tenantId, "local", fp, summary())));
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
                return asTenant(tenantId, () -> upserter.upsert(tenantId, "local", fp, summary()));   // 스레드마다 set
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

        asTenant(teamA, () -> upserter.upsert(teamA, "local", fp, summary()));
        asTenant(teamB, () -> upserter.upsert(teamB, "local", fp, summary()));

        assertThat(countGroups(fp)).isEqualTo(2);
    }
}
