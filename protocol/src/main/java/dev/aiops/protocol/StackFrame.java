package dev.aiops.protocol;

/** 스택트레이스 한 줄. lineNumber는 모르면 -1 */
public record StackFrame(
                String className,
                String methodName,
                String fileName,
                int lineNumber) {
}