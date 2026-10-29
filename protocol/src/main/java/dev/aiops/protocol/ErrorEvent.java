package main.java.dev.aiops.protocol;

import java.util.List;

/**
 * 에러 1건.
 * time: epoch millis (UTC)
 * stack: 바깥 예외의 프레임, 최대 ProtocolConstants.MAX_STACK_FRAMES
 * causes: 원인 예외 체인 (바깥 → 안쪽 순)
 * message: 마스킹된 메시지, 최대 ProtocolConstants.MAX_MESSAGE_LENGTH
 */
public record ErrorEvent(
        long time,
        String level,
        String exceptionType,
        String message,
        List<StackFrame> stack,
        List<ErrorCause> causes,
        String thread,
        Phase phase) {
    public ErrorEvent {
        stack = stack == null ? List.of() : List.copyOf(stack);
        causes = causes == null ? List.of() : List.copyOf(causes);
    }
}