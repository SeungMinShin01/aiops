package dev.aiops.server.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ErrorsStoredListener {

    private final ErrorProcessor errorProcessor;

    public ErrorsStoredListener(ErrorProcessor errorProcessor) {
        this.errorProcessor = errorProcessor;
    }

    // 저장이 커밋된 뒤에만 호출됨. 롤백되면 호출 안 됨 (AI-32)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onErrorsStored(ErrorsStored event) {
        // AI-50 : 처리기를 바로 부름. @Async 라서 바로 돌아오고 실제 처리는 처리 풀이 함
        errorProcessor.process(event.tenantId(), event.eventIds());
    }
}