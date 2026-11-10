package dev.aiops.server.security;

/**
 * 팀이 정해져야 하는 곳에서 TenantContext가 비어 있을 때 쓰는 내부 신호.
 * 사용자에게 500으로 보여 줄 에러가 아니라, 로그를 남기고 0건으로 동작하게 하기 위한 것이다 (D-017).
 */
public class TenantNotSetException extends RuntimeException {

    public TenantNotSetException() {
        super("TenantContext가 비어 있다 (팀 미설정)");
    }
}
