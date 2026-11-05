package dev.aiops.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aiops.server.model.entity.IngestStatEntity;
import dev.aiops.server.model.repository.IngestStatRepository;
import dev.aiops.server.security.TenantContext;
import dev.aiops.server.support.PostgresContainerSupport;
import java.time.LocalDate;
import java.time.ZoneId;
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
import org.springframework.transaction.support.TransactionTemplate;

// AI-62 : 진짜 Postgres 로 ON CONFLICT 누적을 검사. 스레드 여러 개가 동시에 쓰는 걸 보려고
// 테스트마다 실제로 커밋하는 방식 (그래서 팀 이름은 랜덤, 팀마다 따로 확인)
// AI-16 병합(RLS): 실제 서버에서 record 는 수집 요청 스레드(키 필터가 팀을 set 한 상태)에서만 불린다.
// 테스트도 같은 조건으로: 부르는 스레드마다 TenantContext set/clear, 조회도 그 팀으로 트랜잭션 안에서
@SpringBootTest
class IngestStatRecorderTest extends PostgresContainerSupport {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired IngestStatRecorder recorder;
    @Autowired IngestStatRepository stats;
    @Autowired TransactionTemplate tx;

    private JdbcTemplate owner;

    @BeforeEach
    void setUp() {
        owner = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), OWNER_USER, OWNER_PASSWORD));
    }

    private Long newTenant() {
        return owner.queryForObject("INSERT INTO tenant (name) VALUES (?) RETURNING id", Long.class,
                "stat-" + UUID.randomUUID().toString().substring(0, 8));
    }

    // 요청 스레드처럼: 이 팀으로 set → 트랜잭션 안에서 일 → clear
    private <T> T asTenant(Long tenantId, Supplier<T> work) {
        TenantContext.set(tenantId);
        try {
            return tx.execute(s -> work.get());
        } finally {
            TenantContext.clear();
        }
    }

    private void record(Long tenantId, String env, int received, int dropped) {
        asTenant(tenantId, () -> {
            recorder.record(tenantId, env, received, dropped);
            return null;
        });
    }

    // 오늘 전후 하루까지 넉넉히 조회 (자정 근처에 돌려도 안전하게)
    private List<IngestStatEntity> rows(Long tenantId) {
        LocalDate today = LocalDate.now(SEOUL);
        return asTenant(tenantId, () -> stats.findByTenantAndRange(tenantId, today.minusDays(1), today.plusDays(1)));
    }

    @Test
    void sameDayAccumulates() {
        Long team = newTenant();

        record(team, "local", 3, 1);
        record(team, "local", 5, 0);

        // 같은 팀·env·날 → 행은 1개, 받은 8, 버린 1
        List<IngestStatEntity> rows = rows(team);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getReceivedCount()).isEqualTo(8);
        assertThat(rows.get(0).getDroppedCount()).isEqualTo(1);
    }

    @Test
    void separateRows() {
        Long teamA = newTenant();
        Long teamB = newTenant();

        record(teamA, "local", 1, 0);
        record(teamA, "prod", 2, 0);   // 같은 팀이라도 env 가 다르면 다른 행
        record(teamB, "local", 4, 0);  // 다른 팀이면 다른 행

        assertThat(rows(teamA)).hasSize(2);
        assertThat(rows(teamB)).hasSize(1);
        assertThat(rows(teamB).get(0).getReceivedCount()).isEqualTo(4);
    }

    @Test
    void concurrent() throws Exception {
        Long team = newTenant();
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);   // 모두 준비되면 한꺼번에 출발시키는 신호

        List<Future<Void>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<Void> task = () -> {
                start.await();
                record(team, "local", 10, 0);   // 스레드마다 10건 받음 (스레드마다 팀 set)
                return null;
            };
            futures.add(pool.submit(task));
        }
        start.countDown();
        for (Future<Void> future : futures) {
            future.get();   // 하나라도 예외가 났으면 여기서 테스트 실패
        }
        pool.shutdown();

        // 10 스레드 × 10건 = 100, 행은 1개 (중복 행·유니크 위반 없음)
        List<IngestStatEntity> rows = rows(team);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getReceivedCount()).isEqualTo(100);
    }

    @Test
    void negativeDroppedBecomesZero() {
        Long team = newTenant();

        record(team, "local", 2, -5);

        List<IngestStatEntity> rows = rows(team);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getReceivedCount()).isEqualTo(2);
        assertThat(rows.get(0).getDroppedCount()).isZero();   // 음수는 0 으로 보정
    }

    @Test
    void dayIsSeoulDate() {
        Long team = newTenant();

        LocalDate before = LocalDate.now(SEOUL);
        record(team, "local", 1, 0);
        LocalDate after = LocalDate.now(SEOUL);

        // 기록된 날짜가 서울 기준 오늘 (실행 중 자정을 넘길 수 있어 전후 둘 중 하나)
        assertThat(rows(team).get(0).getDay()).isIn(before, after);
    }

    @Test
    void rangeQueryFiltersByDate() {
        Long team = newTenant();
        record(team, "local", 1, 0);

        LocalDate today = LocalDate.now(SEOUL);
        // 내일~모레만 조회하면 오늘 행은 안 나옴
        assertThat(asTenant(team, () -> stats.findByTenantAndRange(team, today.plusDays(1), today.plusDays(2))))
                .isEmpty();
    }

    // AI-16 병합(RLS): 팀 설정 없이 부르면 RLS WITH CHECK 에 막힌다 (다른 팀 번호로 몰래 쓰기 불가)
    @Test
    void 다른_팀으로_설정된_상태에서는_쓸_수_없다() {
        Long teamA = newTenant();
        Long teamB = newTenant();

        TenantContext.set(teamA);
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> tx.execute(s -> {
                recorder.record(teamB, "local", 1, 0);   // 팀 A 로 설정했는데 팀 B 행을 쓰려 함
                return null;
            })).isInstanceOf(RuntimeException.class);
        } finally {
            TenantContext.clear();
        }
        assertThat(owner.queryForObject("select count(*) from ingest_stat where tenant_id = ?", Long.class, teamB))
                .isZero();
    }
}
