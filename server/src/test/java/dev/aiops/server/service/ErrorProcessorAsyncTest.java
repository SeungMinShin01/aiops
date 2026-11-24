package dev.aiops.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.aiops.server.support.PostgresContainerSupport;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

// AI-50 : process 가 호출한 스레드가 아니라 처리 풀(aiops-proc-) 스레드에서 도는지 확인
@SpringBootTest
class ErrorProcessorAsyncTest extends PostgresContainerSupport {

    @Autowired ErrorProcessor errorProcessor;

    @Test
    void runsOnProcessingThread() {
        // process 가 남기는 로그를 모아두는 상자. 로그에는 "어느 스레드가 썼는지"가 같이 기록됨
        Logger logger = (Logger) LoggerFactory.getLogger(ErrorProcessor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            errorProcessor.process(1L, List.of(10L, 11L));

            // 처리는 다른 스레드에서 돌아서, 로그가 찍힐 때까지 최대 3초 기다림
            await().atMost(Duration.ofSeconds(3)).until(() -> !appender.list.isEmpty());

            assertThat(appender.list.get(0).getThreadName()).startsWith("aiops-proc-");
        } finally {
            logger.detachAppender(appender);   // 다른 테스트에 영향 없게 치움
        }
    }
}