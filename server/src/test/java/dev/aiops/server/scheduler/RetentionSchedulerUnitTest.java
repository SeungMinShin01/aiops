package dev.aiops.server.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.aiops.server.model.entity.TenantEntity;
import dev.aiops.server.model.repository.TenantRepository;
import dev.aiops.server.security.TenantContext;
import dev.aiops.server.service.ErrorEventPurger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

// AI-61 : DB 없이 스케줄러의 약속만 확인 (가짜 Purger 사용)
// AI-16 병합(RLS): 스케줄러가 팀 목록을 돌기 때문에 가짜 팀 목록(팀 1개)을 같이 준다
class RetentionSchedulerUnitTest {

    private static TenantRepository tenantsOf(long... ids) {
        TenantRepository tenants = mock(TenantRepository.class);
        List<TenantEntity> list = new ArrayList<>();
        for (long id : ids) {
            TenantEntity t = new TenantEntity("t" + id);
            ReflectionTestUtils.setField(t, "id", id);
            list.add(t);
        }
        when(tenants.findAll()).thenReturn(list);
        return tenants;
    }

    @Test
    void 삭제가_실패해도_예외를_밖으로_던지지_않고_prod도_시도한다() {
        ErrorEventPurger purger = mock(ErrorEventPurger.class);
        when(purger.purgeBatch(eq("local"), any(), anyInt())).thenThrow(new RuntimeException("boom"));
        when(purger.purgeBatch(eq("prod"), any(), anyInt())).thenReturn(0);
        RetentionScheduler scheduler = new RetentionScheduler(purger, tenantsOf(1L), 3, 14, 5000, 0);

        // local 삭제가 터져도 run() 은 정상 종료, prod 는 계속 진행
        assertThatCode(scheduler::run).doesNotThrowAnyException();
        verify(purger).purgeBatch(eq("prod"), any(), eq(5000));
    }

    @Test
    void 기준_시각은_보관_일수만큼_과거다() {
        ErrorEventPurger purger = mock(ErrorEventPurger.class);
        when(purger.purgeBatch(any(), any(), anyInt())).thenReturn(0);
        RetentionScheduler scheduler = new RetentionScheduler(purger, tenantsOf(1L), 3, 14, 5000, 0);

        Instant before = Instant.now();
        scheduler.run();
        Instant after = Instant.now();

        ArgumentCaptor<Instant> local = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> prod = ArgumentCaptor.forClass(Instant.class);
        verify(purger).purgeBatch(eq("local"), local.capture(), eq(5000));
        verify(purger).purgeBatch(eq("prod"), prod.capture(), eq(5000));

        // local 은 3일 전, prod 는 14일 전 (실행 전후 시각 사이에서 계산됨)
        assertThat(local.getValue()).isBetween(before.minus(Duration.ofDays(3)), after.minus(Duration.ofDays(3)));
        assertThat(prod.getValue()).isBetween(before.minus(Duration.ofDays(14)), after.minus(Duration.ofDays(14)));
    }

    // AI-73 : 팀마다 TenantContext 를 그 팀으로 맞춰 지우고, 끝나면 비운다
    @Test
    void 팀마다_TenantContext를_설정해서_지우고_끝나면_비운다() {
        List<Long> seen = new ArrayList<>();
        ErrorEventPurger purger = mock(ErrorEventPurger.class);
        when(purger.purgeBatch(any(), any(), anyInt())).thenAnswer(inv -> {
            seen.add(TenantContext.require());
            return 0;
        });
        RetentionScheduler scheduler = new RetentionScheduler(purger, tenantsOf(7L, 8L), 3, 14, 5000, 0);

        scheduler.run();

        assertThat(seen).containsExactly(7L, 7L, 8L, 8L);   // 팀마다 local·prod
        assertThat(TenantContext.get()).isEmpty();
    }
}
