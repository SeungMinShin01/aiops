package dev.aiops.starter.runtime;

import dev.aiops.starter.config.StarterVersion;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;

/**
 * Starter 진입점 (부품 ②). spring.factories로 등록한다 (D-005).
 * Bean보다 먼저 실행되므로 Bean 생성 실패도 잡을 수 있다.
 * 순서는 로깅 초기화 직후: 그 전에 어펜더를 붙이면 로깅 초기화가 떼어 버린다.
 */
public final class AiopsEarlyInitializer
        implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

    /** LoggingApplicationListener(HIGHEST_PRECEDENCE + 20) 바로 다음 */
    static final int ORDER = LoggingApplicationListener.DEFAULT_ORDER + 1;

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        try {
            AiopsRuntime.startIfAbsent(event.getEnvironment());
            // 4단계(AI-43)에서 여기에 어펜더 장착(attachAppenderIfMissing)이 들어온다
        } catch (Throwable t) {
            System.err.println("[aiops] 비활성화됨: " + t.getClass().getName() + " " + t.getMessage()
                    + " (starter " + StarterVersion.get() + ")");
        }
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}