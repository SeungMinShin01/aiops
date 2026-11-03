package dev.aiops.server.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import dev.aiops.server.support.PostgresContainerSupport;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;


@SpringBootTest   // 진짜 스프링 전체를 띄움 (리스너가 스프링 안에서만 동작하기 때문)
class ErrorsStoredListenerTest extends PostgresContainerSupport {   // 진짜 Postgres 를 도커로 띄우는 공통 부모

    @Autowired ApplicationEventPublisher publisher;   // "저장됐다" 알림을 던지는 도구 (IngestService 가 쓰는 것과 같음)
    @Autowired TransactionTemplate tx;                // 코드 한 덩어리를 "트랜잭션 하나"로 실행해주는 도구
    @MockitoBean ErrorProcessor errorProcessor;       // 가짜 처리기. 호출됐는지만 기록함

    @Test
    void 커밋되면_처리_요청이_한번_간다() {
        // 트랜잭션 안에서 알림을 던지고, 블록이 끝나면 커밋됨 → 리스너가 깨어남
        tx.executeWithoutResult(s -> publisher.publishEvent(new ErrorsStored(1L, List.of(10L, 11L))));
        // 가짜 처리기가 (1, [10, 11]) 로 정확히 1번 불렸는지 확인
        verify(errorProcessor).process(1L, List.of(10L, 11L));
    }

    @Test
    void 롤백되면_처리_요청이_가지_않는다() {
        tx.executeWithoutResult(s -> {
            publisher.publishEvent(new ErrorsStored(1L, List.of(10L)));
            s.setRollbackOnly();   // "이 트랜잭션은 취소"라고 표시 → 커밋 안 됨
        });
        // 커밋이 없었으니 가짜 처리기는 한 번도 안 불려야 함
        verify(errorProcessor, never()).process(any(), any());
    }
}