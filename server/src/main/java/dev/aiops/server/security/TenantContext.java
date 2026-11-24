package dev.aiops.server.security;

import java.util.Optional;

/**
 * "지금 어느 팀인가"를 스레드 단위로 기억한다 (AI-16, D-018).
 * <p>
 * 이 값은 트랜잭션이 열릴 때 {@link TenantTransactionManager}가 {@code set_config('app.tenant_id', ?, true)}로
 * DB에 넘기고, RLS 정책이 그 값으로 행을 거른다.
 * <ul>
 *   <li>set한 곳이 finally에서 clear한다. 예외가 나도 지워야 다음 요청이 이전 팀을 물려받지 않는다.</li>
 *   <li>다른 스레드로는 넘어가지 않는다. 처리 풀로 넘길 때는 {@code process(tenantId, ids)} 파라미터로 넘기고
 *       그 스레드에서 다시 set한다. InheritableThreadLocal·TaskDecorator 복사 금지.</li>
 *   <li>비었을 때 "기본 팀" 같은 대체값은 없다 (D-017 fail closed).</li>
 * </ul>
 * 순수 static 유틸 — Spring Bean으로 만들지 않는다 (트랜잭션 매니저가 Bean 생성 순서와 무관하게 부를 수 있어야 함).
 */
public final class TenantContext {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    /** 현재 스레드의 팀을 정한다. null이면 IllegalArgumentException */
    public static void set(Long tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId는 null일 수 없다");
        }
        CURRENT.set(tenantId);
    }

    /** 설정 안 됐으면 Optional.empty() */
    public static Optional<Long> get() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** 없으면 TenantNotSetException */
    public static Long require() {
        Long tenantId = CURRENT.get();
        if (tenantId == null) {
            throw new TenantNotSetException();
        }
        return tenantId;
    }

    /** 현재 스레드의 팀을 지운다 */
    public static void clear() {
        CURRENT.remove();
    }
}
