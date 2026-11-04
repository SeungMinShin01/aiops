package dev.aiops.server.service;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

// 저장된 에러 이벤트를 처리하는 입구 (D-081: process(tenantId, eventIds) 고정)
@Service
public class ErrorProcessor {

    private static final Logger log = LoggerFactory.getLogger(ErrorProcessor.class);

    // AI-50 : 처리 풀(aiops-proc-)에서 실행됨. 호출한 쪽은 기다리지 않고 바로 돌아감
    // 지금은 받은 팀·개수 로그만. 지문(AI-51)·그룹 upsert(AI-52)는 다음 작업에서 채움
    @Async("aiopsProcessingExecutor")
    public void process(Long tenantId, List<Long> eventIds) {
        try {
            log.info("처리 요청 받음 tenantId={} count={}", tenantId, eventIds.size());
        } catch (RuntimeException e) {
            // AI-50 : @Async 메서드의 예외는 조용히 사라지므로 직접 잡아서 로그
            log.error("처리 실패 tenantId={} eventIds={}", tenantId, eventIds, e);
        }
    }
}