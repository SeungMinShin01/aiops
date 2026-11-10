package dev.aiops.protocol;

import java.util.List;

/**
 * 에러 1건.
 * time: epoch millis (UTC)
 * stack: 바깥 예외의 프레임, 최대 ProtocolConstants.MAX_STACK_FRAMES
 * causes: 원인 예외 체인 (바깥 → 안쪽 순)
 * message: 마스킹된 메시지, 최대 ProtocolConstants.MAX_MESSAGE_LENGTH
 * phase: STARTUP / RUNTIME. null이면 보낸 쪽 버그 (그대로 저장해서 드러나게 한다)
 * loggerName: 로그를 찍은 로거 이름. 로거 없이 만든 이벤트(시작 실패 등)는 null
 */
public record ErrorEvent(
        long time,
        String level,
        String exceptionType,
        String message,
        List<StackFrame> stack,
        List<ErrorCause> causes,
        String thread,
        Phase phase,
        String loggerName) {

    public ErrorEvent {
        stack = stack == null ? List.of() : List.copyOf(stack);
        causes = causes == null ? List.of() : List.copyOf(causes);
    }

    /** loggerName 추가 전 형태. 기존 코드·테스트가 그대로 컴파일되게 남긴다 (loggerName = null) */
    public ErrorEvent(long time, String level, String exceptionType, String message,
            List<StackFrame> stack, List<ErrorCause> causes, String thread, Phase phase) {
        this(time, level, exceptionType, message, stack, causes, thread, phase, null);
    }
}