package dev.aiops.server.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import dev.aiops.server.security.TenantContext;
import dev.aiops.server.service.ErrorProcessor;
import dev.aiops.server.support.PostgresContainerSupport;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

// AI-53 : run() 을 직접 불러서 확인 (5분을 기다리지 않음)
// AI-16 병합(RLS): 클래스 @Transactional 을 두지 않는다. 테스트 트랜잭션에 스케줄러 조회가 합류하면
// 팀 설정(set_config)이 안 들어가 0건이 된다. 그래서 owner(RLS 무시)로 넣고 시작할 때 비운다
@SpringBootTest
class ReprocessSchedulerTest extends PostgresContainerSupport {

    @Autowired ReprocessScheduler scheduler;
    @MockitoBean ErrorProcessor errorProcessor;   // 가짜 처리기: 누구를 불렀는지만 기록함

    private JdbcTemplate owner;

    @BeforeEach
    void clean() {
        owner = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), OWNER_USER, OWNER_PASSWORD));
        owner.execute("TRUNCATE error_event, error_group RESTART IDENTITY CASCADE");
    }

    private Long newTenant() {
        return owner.queryForObject("INSERT INTO tenant (name) VALUES (?) RETURNING id", Long.class,
                "r-" + UUID.randomUUID().toString().substring(0, 8));
    }

    // 처리 안 된 이벤트를 받은 시각 secondsAgo 초 전으로 저장
    private Long saveEvent(Long tenantId, int secondsAgo) {
        return owner.queryForObject("INSERT INTO error_event (tenant_id, env, payload, received_at) "
                        + "VALUES (?, 'local', '{}'::jsonb, now() - (? * interval '1 second')) RETURNING id",
                Long.class, tenantId, secondsAgo);
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

    // AI-73 : 스케줄러 스레드에 팀이 남지 않는다 (처리기 호출 시점에도, 끝난 뒤에도)
    @Test
    void 팀을_돌고_나면_TenantContext가_비어_있다() {
        Long team = newTenant();
        saveEvent(team, 600);
        doAnswer(inv -> {
            assertThat(TenantContext.get()).as("조회가 끝나면 clear, 팀은 파라미터로만 넘긴다").isEmpty();
            return null;
        }).when(errorProcessor).process(eq(team), anyList());

        scheduler.run();

        verify(errorProcessor).process(eq(team), anyList());
        assertThat(TenantContext.get()).isEmpty();
    }
}
