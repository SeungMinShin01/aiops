package dev.aiops.server.scheduler;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import dev.aiops.server.model.entity.ErrorEventEntity;
import dev.aiops.server.model.entity.TenantEntity;
import dev.aiops.server.model.repository.ErrorEventRepository;
import dev.aiops.server.model.repository.TenantRepository;
import dev.aiops.server.service.ErrorProcessor;
import dev.aiops.server.support.PostgresContainerSupport;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

// AI-53 : run() 을 직접 불러서 확인 (5분을 기다리지 않음). 테스트가 끝나면 DB 는 자동 롤백
@SpringBootTest
@Transactional
class ReprocessSchedulerTest extends PostgresContainerSupport {

    @Autowired ReprocessScheduler scheduler;
    @Autowired ErrorEventRepository events;
    @Autowired TenantRepository tenants;
    @Autowired EntityManager em;
    @MockitoBean ErrorProcessor errorProcessor;   // 가짜 처리기: 누구를 불렀는지만 기록함

    private Long newTenant() {
        return tenants.save(new TenantEntity("r-" + UUID.randomUUID().toString().substring(0, 8))).getId();
    }

    // 처리 안 된 이벤트를 저장하고, 받은 시각을 secondsAgo 초 전으로 바꿈
    private Long saveEvent(Long tenantId, int secondsAgo) {
        Long id = events.save(new ErrorEventEntity(tenantId, "local", "{}", "sample", "abc", "abc123def456")).getId();
        em.createNativeQuery(
                        "update error_event set received_at = now() - (CAST(:sec AS integer) * interval '1 second') where id = :id")
                .setParameter("sec", secondsAgo)
                .setParameter("id", id)
                .executeUpdate();
        return id;
    }

    @Test
    void picksUpFailedEvents() {
        Long teamA = newTenant();
        Long teamB = newTenant();
        Long a1 = saveEvent(teamA, 600);   // 10분 전
        Long a2 = saveEvent(teamA, 600);
        Long b1 = saveEvent(teamB, 600);

        scheduler.run();

        // 팀마다 정확히 1번씩, 그 팀의 이벤트 id 만 담아서 호출
        verify(errorProcessor).process(eq(teamA), argThat(ids -> ids.size() == 2 && ids.containsAll(List.of(a1, a2))));
        verify(errorProcessor).process(eq(teamB), argThat(ids -> ids.size() == 1 && ids.contains(b1)));
    }

    @Test
    void skipsFreshEvents() {
        Long team = newTenant();
        saveEvent(team, 30);   // 30초 전: 아직 유예 시간(2분) 안

        scheduler.run();

        verify(errorProcessor, never()).process(eq(team), anyList());
    }

    @Test
    void 예외가_나도_다음_run은_정상이다() {
        Long teamA = newTenant();
        Long teamB = newTenant();
        saveEvent(teamA, 600);
        saveEvent(teamB, 600);
        doThrow(new RuntimeException("boom")).when(errorProcessor).process(eq(teamA), anyList());

        // 팀 A 처리 요청이 터져도 run() 은 예외를 밖으로 안 던지고, 팀 B 는 계속 처리됨
        assertThatCode(() -> scheduler.run()).doesNotThrowAnyException();
        verify(errorProcessor).process(eq(teamB), anyList());

        // 문제가 사라지면 다음 run() 은 팀 A 도 정상 호출 (처리 안 된 채로 남아 있으니 또 줍는 것)
        doNothing().when(errorProcessor).process(eq(teamA), anyList());
        scheduler.run();
        verify(errorProcessor, times(2)).process(eq(teamA), anyList());
    }
} 