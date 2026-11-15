package dev.aiops.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aiops.server.support.PostgresContainerSupport;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

// AI-57 : 일일 상한. 다른 테스트와 겹치지 않게 아주 옛 날짜로 센다 (오늘 칸은 건드리지 않음)
@SpringBootTest
class LlmQuotaTest extends PostgresContainerSupport {

    static final LocalDate DAY_A = LocalDate.of(2001, 1, 1);
    static final LocalDate DAY_B = LocalDate.of(2001, 1, 2);

    @Autowired
    LlmQuota quota;

    private JdbcTemplate owner;

    @BeforeEach
    void setUp() {
        owner = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), OWNER_USER, OWNER_PASSWORD));
        owner.update("delete from llm_usage where day < '2002-01-01'");
    }

    private Integer countOf(LocalDate day) {
        List<Integer> rows = owner.queryForList("select call_count from llm_usage where day = ?",
                Integer.class, Date.valueOf(day));
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Test
    void stopsAtLimit() {
        assertThat(quota.tryAcquire(DAY_A, 3)).isTrue();
        assertThat(quota.tryAcquire(DAY_A, 3)).isTrue();
        assertThat(quota.tryAcquire(DAY_A, 3)).isTrue();
        assertThat(quota.tryAcquire(DAY_A, 3)).isFalse();

        assertThat(countOf(DAY_A)).isEqualTo(3);
    }

    @Test
    void concurrent() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            results.add(pool.submit(() -> quota.tryAcquire(DAY_B, 5)));
        }
        int allowed = 0;
        for (Future<Boolean> result : results) {
            if (result.get(30, TimeUnit.SECONDS)) {
                allowed++;
            }
        }
        pool.shutdown();

        assertThat(allowed).isEqualTo(5);
        assertThat(countOf(DAY_B)).isEqualTo(5);
    }

    @Test
    void 상한이_0이면_항상_거절하고_행도_만들지_않는다() {
        assertThat(quota.tryAcquire(DAY_A, 0)).isFalse();

        assertThat(countOf(DAY_A)).isNull();
    }

    @Test
    void 날짜가_바뀌면_다시_센다() {
        assertThat(quota.tryAcquire(DAY_A, 1)).isTrue();
        assertThat(quota.tryAcquire(DAY_A, 1)).isFalse();

        assertThat(quota.tryAcquire(DAY_B, 1)).isTrue();
    }

    @Test
    void 오늘_날짜와_설정된_상한으로도_동작한다() {
        assertThat(quota.tryAcquire(1L)).isTrue();
    }
}