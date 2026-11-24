package dev.aiops.protocol;

/** 스택트레이스 한 줄. lineNumber는 StackTraceElement 값 그대로 (-1 모름, -2 네이티브 메서드) */
public record StackFrame(
        String className,
        String methodName,
        String fileName,
        int lineNumber) {
}