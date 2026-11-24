package dev.aiops.server.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

// AI-50 : 수집(저장)과 처리(그룹화·LLM·알림)를 스레드로 분리하는 설정
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    // AI-50 : 처리 전용 스레드 풀. 스레드 수와 대기줄 크기는 yml 값 (코드에 박지 않음)
    @Bean("aiopsProcessingExecutor")
    public ThreadPoolTaskExecutor aiopsProcessingExecutor(
            @Value("${aiops.processing.threads:4}") int threads,
            @Value("${aiops.processing.queue:1000}") int queue) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);                 // 기본 일꾼 수
        executor.setMaxPoolSize(threads);                  // 최대도 같게 (대기줄이 차야 늘어나는 구조라 의미 없음)
        executor.setQueueCapacity(queue);                  // 일꾼이 다 바쁠 때 줄 서는 칸 수
        executor.setThreadNamePrefix("aiops-proc-");       // 스레드 이름표 (로그에서 처리 풀을 구분)
        // 줄까지 꽉 차면 거절하지 않고 호출한 스레드가 직접 처리 (이벤트를 잃는 것보다 나음)
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true); // 서버 종료 때 하던 일은 마저 끝냄
        executor.setAwaitTerminationSeconds(10);            // 단, 최대 10초까지만 기다림
        return executor;
    }

    // AI-50 : @Async void 메서드에서 터진 예외는 아무도 못 받아서, 여기서 로그로 남김
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) -> log.error("처리 풀 예외 method={}", method.getName(), ex);
    }
}