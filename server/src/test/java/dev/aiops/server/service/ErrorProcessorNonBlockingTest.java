package dev.aiops.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aiops.server.support.PostgresContainerSupport;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.support.TransactionTemplate;

// AI-50 : 처리가 3초 걸려도 저장(커밋) 쪽은 기다리지 않고 바로 끝나는지 확인
@SpringBootTest
class ErrorProcessorNonBlockingTest extends PostgresContainerSupport {

    @Autowired ApplicationEventPublisher publisher;
    @Autowired TransactionTemplate tx;

    // 이 테스트에서만 진짜 처리기 대신 "3초 걸리는 처리기"를 끼움 (@Async 는 그대로 붙어 있어야 의미가 있음)
    @TestConfiguration
    static class SlowProcessorConfig {
        @Bean
        @Primary
        ErrorProcessor slowErrorProcessor() {
            return new SlowErrorProcessor();
        }
    }

    static class SlowErrorProcessor extends ErrorProcessor {
        SlowErrorProcessor() {
            super(null);   // AI-52 : 부모가 GroupingService 를 받게 돼서 필요. 이 처리기는 쓰지 않음
        }

        @Override
        @Async("aiopsProcessingExecutor")
        public void process(Long tenantId, List<Long> eventIds) {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    void ingestDoesNotWaitForProcessing() {
        long start = System.nanoTime();

        // 커밋되면 리스너가 처리기를 부름. 처리가 동기로 돌면 여기서 3초가 걸림
        tx.executeWithoutResult(s -> publisher.publishEvent(new ErrorsStored(1L, List.of(10L))));

        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        assertThat(elapsed).isLessThan(Duration.ofMillis(500));
    }
}