package dev.aiops.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ErrorsStoredListener {

    private static final Logger log = LoggerFactory.getLogger(ErrorsStoredListener.class);

    // 처리기 구현체가 아직 없어도 서버가 뜨게 ObjectProvider 로 받음
    private final ObjectProvider<ErrorProcessor> errorProcessor;

    public ErrorsStoredListener(ObjectProvider<ErrorProcessor> errorProcessor) {
        this.errorProcessor = errorProcessor;
    }

    // 저장이 커밋된 뒤에만 호출됨. 롤백되면 호출 안 됨
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onErrorsStored(ErrorsStored event) {
        try {
            // 실제 비동기(@Async) 처리는 처리기 쪽 몫. 여기서는 요청만 넘김
            errorProcessor.ifAvailable(p -> p.process(event.tenantId(), event.eventIds()));
        } catch (RuntimeException e) {
            // 이미 202 응답 나간 뒤라 던질 곳이 없음. processed=false 로 남아 재처리됨
            log.error("처리 요청 실패 tenantId={} eventIds={}", event.tenantId(), event.eventIds(), e);
        }
    }
}