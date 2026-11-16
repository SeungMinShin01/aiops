package dev.aiops.starter.capture;

import ch.qos.logback.classic.spi.ILoggingEvent;
import dev.aiops.protocol.ErrorEvent;
import dev.aiops.protocol.Phase;

/**
 * 부품 ④. 예외를 protocol ErrorEvent로 바꾼다. 마스킹은 하지 않는다 (다음 단계 Masker).
 * 팀 앱 스레드에서 돌므로 빠르고, 실패하면 null을 돌려준다 (호출한 쪽이 건너뜀).
 */
public interface EventMapper {

    /** 어펜더(AI-42)가 부른다. 예외가 붙지 않은 로그면 null */
    ErrorEvent map(ILoggingEvent event, Phase phase);

    /** 시작 실패(5단계, ApplicationFailedEvent)에서 Logback 없이 직접 부른다. 같은 규칙 */
    ErrorEvent mapThrowable(Throwable throwable, Phase phase);
}