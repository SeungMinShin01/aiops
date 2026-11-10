package dev.aiops.server.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;

import java.util.List;

/**
 * /v1/** 키 인증 결과 (AI-79). principal = 팀 번호, env = 키의 환경. 키 원문은 들고 있지 않는다.
 * 사람 로그인(AppUserPrincipal)과 섞이지 않는다 — /v1 체인에서만 만들어진다.
 */
public class ApiKeyAuthentication extends AbstractAuthenticationToken {

    private final long tenantId;
    private final String env;

    public ApiKeyAuthentication(long tenantId, String env) {
        super(List.of());
        this.tenantId = tenantId;
        this.env = env;
        setAuthenticated(true);
    }

    @Override
    public Object getPrincipal() {
        return tenantId;
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    public long tenantId() {
        return tenantId;
    }

    public String env() {
        return env;
    }
}
