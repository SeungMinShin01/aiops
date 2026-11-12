package dev.aiops.starter.runtime;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;

/**
 * Starter 진입점 (부품 ②). spring.factories로 등록한다 (D-005).
 * Bean보다 먼저 실행되므로 Bean 생성 실패도 잡을 수 있다.
 * 순서는 로깅 초기화 직후: 그 전에 어펜더를 붙이면 로깅 초기화가 떼어 버린다.
 * 장착은 세 시점에서 확인한다 — 중간에 Logback이 리셋돼도 다시 붙는다.
 * 테스트가 handle()을 덮어써 Error를 흉내 낼 수 있도록 final이 아니다.
 */
public class AiopsEarlyInitializer implements ApplicationListener<ApplicationEvent>, Ordered {

    /** LoggingApplicationListener(HIGHEST_PRECEDENCE + 20) 바로 다음 */
    static final int ORDER = LoggingApplicationListener.DEFAULT_ORDER + 1;

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        try {
            if (AiopsRuntime.isDisabled()) {
                return;                           // 한 번 꺼지면 이후 이벤트는 바로 리턴
            }
            handle(event);
        } catch (Throwable t) {
            AiopsRuntime.disable(t);             // Exception이 아니라 Throwable (D-087)
        }
    }

    void handle(ApplicationEvent event) {
        if (event instanceof ApplicationEnvironmentPreparedEvent prepared) {
            if (AiopsRuntime.startIfAbsent(prepared.getEnvironment()) != null) {
                AiopsRuntime.attachAppenderIfMissing();
            }
        } else if (event instanceof ApplicationPreparedEvent || event instanceof ApplicationReadyEvent) {
            AiopsRuntime.attachAppenderIfMissing();
        }
        // 그 밖의 이벤트는 무시. 5단계에서 ApplicationFailedEvent(시작 실패)가 여기 들어온다
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}