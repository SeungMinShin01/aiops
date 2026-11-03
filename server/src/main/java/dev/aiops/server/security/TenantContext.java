package dev.aiops.server.security;

import java.util.Optional;

/**
 * "지금 어느 팀인가"를 스레드 단위로 기억한다 (AI-16, D-018).
 * 설계자가 시그니처만 먼저 둔다. 구현은 AI-70 (ThreadLocal, set한 곳이 finally에서 clear).
 * 순수 static 유틸 — Spring Bean으로 만들지 않는다.
 */
public final class TenantContext {

    private TenantContext() {
    }

    /** 현재 스레드의 팀을 정한다. null이면 IllegalArgumentException */
    public static void set(Long tenantId) {
        throw new UnsupportedOperationException("AI-70에서 구현");
    }

    /** 설정 안 됐으면 Optional.empty() */
    public static Optional<Long> get() {
        throw new UnsupportedOperationException("AI-70에서 구현");
    }

    /** 없으면 TenantNotSetException (AI-70에서 예외 클래스와 함께) */
    public static Long require() {
        throw new UnsupportedOperationException("AI-70에서 구현");
    }

    /** 현재 스레드의 팀을 지운다 */
    public static void clear() {
        throw new UnsupportedOperationException("AI-70에서 구현");
    }
}