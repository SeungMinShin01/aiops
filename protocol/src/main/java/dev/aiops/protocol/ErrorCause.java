package main.java.dev.aiops.protocol;

/** 원인 예외(cause) 체인의 한 칸. 스택은 싣지 않고 타입·메시지만 */
public record ErrorCause(
        String type,
        String message) {
}