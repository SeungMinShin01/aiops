package dev.aiops.starter.capture;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import dev.aiops.protocol.ErrorCause;
import dev.aiops.protocol.ErrorEvent;
import dev.aiops.protocol.Phase;
import dev.aiops.protocol.ProtocolConstants;
import dev.aiops.protocol.StackFrame;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * EventMapper 구현 (부품 ④).
 * 스택은 바깥 예외의 프레임만 최대 30줄, 원인 예외는 타입·메시지만 최대 10단 (D-013).
 * 값은 원본 그대로 옮긴다: 프레임을 거르지 않고(팀 코드 판별은 서버 지문), 줄 번호·phase도 바꾸지 않는다.
 */
public final class DefaultEventMapper implements EventMapper {

    /** 원인 체인 최대 단수. 순환하거나 아주 깊어도 여기서 멈춘다 */
    static final int MAX_CAUSES = 10;

    private static final int MAX_FRAMES = ProtocolConstants.MAX_STACK_FRAMES;
    private static final String LEVEL_ERROR = "ERROR";

    // ---- Logback 경로 (어펜더) ----

    @Override
    public ErrorEvent map(ILoggingEvent event, Phase phase) {
        try {
            if (event == null) {
                return null;
            }
            IThrowableProxy proxy = event.getThrowableProxy();
            if (proxy == null) {
                return null; // 예외 없는 ERROR는 수집 대상이 아니다
            }
            String message = proxy.getMessage();
            if (message == null) {
                message = event.getFormattedMessage(); // 예외 메시지가 없을 때만 로그 메시지로 대신
            }
            String level = event.getLevel() == null ? LEVEL_ERROR : event.getLevel().toString();
            return new ErrorEvent(
                    event.getTimeStamp(),
                    level,
                    proxy.getClassName(),
                    message,
                    framesOf(proxy),
                    causesOf(proxy),
                    event.getThreadName(),
                    phase,
                    event.getLoggerName());
        } catch (Throwable t) {
            return null; // 그 로그만 건너뛴다 (④)
        }
    }

    private static List<StackFrame> framesOf(IThrowableProxy proxy) {
        StackTraceElementProxy[] array = proxy.getStackTraceElementProxyArray();
        if (array == null) {
            return List.of();
        }
        int n = Math.min(array.length, MAX_FRAMES);
        List<StackFrame> frames = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            if (array[i] != null && array[i].getStackTraceElement() != null) {
                frames.add(toFrame(array[i].getStackTraceElement()));
            }
        }
        return frames;
    }

    private static List<ErrorCause> causesOf(IThrowableProxy proxy) {
        List<ErrorCause> causes = new ArrayList<>();
        // equals가 아니라 "같은 객체인가"로 본다. 이미 본 원인이 다시 나오면 순환이다
        Set<IThrowableProxy> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.add(proxy);
        IThrowableProxy cause = proxy.getCause();
        while (cause != null && causes.size() < MAX_CAUSES && seen.add(cause)) {
            causes.add(new ErrorCause(cause.getClassName(), cause.getMessage()));
            cause = cause.getCause();
        }
        return causes;
    }

    // ---- Throwable 경로 (시작 실패) ----

    @Override
    public ErrorEvent mapThrowable(Throwable throwable, Phase phase) {
        try {
            if (throwable == null) {
                return null;
            }
            return new ErrorEvent(
                    System.currentTimeMillis(),
                    LEVEL_ERROR,
                    throwable.getClass().getName(),
                    throwable.getMessage(),
                    framesOf(throwable.getStackTrace()),
                    causesOf(throwable),
                    Thread.currentThread().getName(),
                    phase,
                    null); // 로거를 거치지 않은 이벤트
        } catch (Throwable t) {
            return null;
        }
    }

    private static List<StackFrame> framesOf(StackTraceElement[] array) {
        if (array == null) {
            return List.of();
        }
        int n = Math.min(array.length, MAX_FRAMES);
        List<StackFrame> frames = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            if (array[i] != null) {
                frames.add(toFrame(array[i]));
            }
        }
        return frames;
    }

    private static List<ErrorCause> causesOf(Throwable throwable) {
        List<ErrorCause> causes = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.add(throwable);
        Throwable cause = throwable.getCause();
        while (cause != null && causes.size() < MAX_CAUSES && seen.add(cause)) {
            causes.add(new ErrorCause(cause.getClass().getName(), cause.getMessage()));
            cause = cause.getCause();
        }
        return causes;
    }

    // ---- 공통 ----

    /** StackTraceElement 값 그대로 (-1 모름, -2 네이티브 메서드) */
    private static StackFrame toFrame(StackTraceElement e) {
        return new StackFrame(e.getClassName(), e.getMethodName(), e.getFileName(), e.getLineNumber());
    }
}