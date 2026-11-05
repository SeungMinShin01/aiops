package dev.aiops.server.service;

import dev.aiops.server.notify.Notifier;
import dev.aiops.server.security.TenantContext;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

// 저장된 에러 이벤트를 처리하는 입구 (D-081: process(tenantId, eventIds) 고정)
@Service
public class ErrorProcessor {

    private static final Logger log = LoggerFactory.getLogger(ErrorProcessor.class);

    // 알림 부품이 없는 생성(단위 테스트용)에서 쓰는 "아무것도 안 하는" 알림
    private static final Notifier NO_NOTIFY = new Notifier() {
        @Override
        public void notifyNewGroup(Long tenantId, Long groupId) {
        }

        @Override
        public void notifyText(Long tenantId, String env, String text) {
        }
    };

    private final GroupingService groupingService;   // 이벤트 1건씩 그룹에 넣는 담당 (AI-52)
    private final Notifier notifier;                 // 새 그룹 알림 (8단계, AI-60에서 연결)

    @Autowired
    public ErrorProcessor(GroupingService groupingService, Notifier notifier) {
        this.groupingService = groupingService;
        this.notifier = notifier;
    }

    // 알림 없이 그룹화만 확인하는 테스트용 (ErrorProcessorTest, SlowErrorProcessor)
    public ErrorProcessor(GroupingService groupingService) {
        this(groupingService, NO_NOTIFY);
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

            // AI-52 : 새로 만들어진 그룹 id 를 모음
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

            // AI-60 : 새 그룹마다 설명 → 알림. 둘 다 트랜잭션 밖이고 서로를 막지 않는다 (각각 try/catch).
            //         같은 그룹 재발생은 newGroupIds 에 없으므로 알림이 다시 가지 않는다.
            //         TenantContext 가 아직 이 팀이라 알림 쪽 그룹 조회도 RLS 를 통과한다
            for (Long groupId : newGroupIds) {
                // TODO(7단계 AI-12): explanationService.explain(tenantId, groupId) 를 여기, 알림 바로 앞에.
                //                    순서 고정: 설명이 먼저여야 알림에 설명이 실린다 (아니면 "설명 준비 중")
                try {
                    notifier.notifyNewGroup(tenantId, groupId);
                } catch (RuntimeException e) {
                    log.warn("알림 실패 tenantId={} groupId={}", tenantId, groupId);   // 웹훅 주소가 섞일 수 있어 예외는 안 넘김
                }
            }

            log.info("새 그룹 {}개 tenantId={}", newGroupIds.size(), tenantId);
        } catch (RuntimeException e) {
            // @Async 메서드의 예외는 조용히 사라지므로 직접 잡아서 로그
            log.error("처리 실패 tenantId={} eventIds={}", tenantId, eventIds, e);
        } finally {
            TenantContext.clear();   // 처리 풀 스레드는 재사용된다 → 다음 작업이 이 팀을 물려받지 않게
        }
    }
}
