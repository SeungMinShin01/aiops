package dev.aiops.server.service;

import dev.aiops.server.security.TenantContext;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

// 저장된 에러 이벤트를 처리하는 입구 (D-081: process(tenantId, eventIds) 고정)
@Service
public class ErrorProcessor {

    private static final Logger log = LoggerFactory.getLogger(ErrorProcessor.class);

    private final GroupingService groupingService;   // 이벤트 1건씩 그룹에 넣는 담당 (AI-52)

    public ErrorProcessor(GroupingService groupingService) {
        this.groupingService = groupingService;
    }

    // AI-50 : 처리 풀(aiops-proc-)에서 실행됨. 호출한 쪽은 기다리지 않고 바로 돌아감
    // AI-73 : 처리 스레드에는 요청 스레드의 TenantContext 가 넘어오지 않는다 (ThreadLocal).
    //         그래서 파라미터로 받은 팀을 첫 줄에서 set 하고 finally 에서 clear (D-018, D-081).
    //         groupingService.groupOne 의 트랜잭션이 열릴 때 바인더가 set_config → RLS 통과
    @Async("aiopsProcessingExecutor")
    public void process(Long tenantId, List<Long> eventIds) {
        TenantContext.set(tenantId);
        try {
            log.info("처리 요청 받음 tenantId={} count={}", tenantId, eventIds.size());

            // AI-52 : 새로 만들어진 그룹 id 를 모음 (다음 단계 LLM·알림은 이후 작업에서 연결)
            List<Long> newGroupIds = new ArrayList<>();
            for (Long eventId : eventIds) {
                try {
                    Long newGroupId = groupingService.groupOne(tenantId, eventId);
                    if (newGroupId != null) {
                        newGroupIds.add(newGroupId);
                    }
                } catch (RuntimeException e) {
                    // 이 이벤트만 실패: processed=false 로 남아 재처리(AI-53)되고, 나머지는 계속 처리
                    log.error("이벤트 처리 실패 tenantId={} eventId={}", tenantId, eventId, e);
                }
            }

            // 트랜잭션 밖. 지금은 로그만
            log.info("새 그룹 {}개 tenantId={}", newGroupIds.size(), tenantId);
        } catch (RuntimeException e) {
            // @Async 메서드의 예외는 조용히 사라지므로 직접 잡아서 로그
            log.error("처리 실패 tenantId={} eventIds={}", tenantId, eventIds, e);
        } finally {
            TenantContext.clear();   // 처리 풀 스레드는 재사용된다 → 다음 작업이 이 팀을 물려받지 않게
        }
    }
}
