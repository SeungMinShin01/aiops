package dev.aiops.server.security;

import dev.aiops.protocol.ProtocolConstants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * /v1/** 키 인증 (AI-79, 11단계 TenantFilter를 흡수).
 * 키 검증은 ApiKeyResolver 한 곳 — 이 필터는 호출만 한다 (규칙 2). 팀은 키로만 정해진다.
 * set한 곳이 finally에서 clear (v0.2 규칙 10).
 * <p>
 * 빈(@Component)으로 만들지 않는다: Filter 빈은 Spring Boot가 모든 경로의 서블릿 필터로 자동 등록해서
 * /api/**에도 타게 된다. SecurityConfig의 /v1 체인에만 직접 끼운다.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    /** 컨트롤러가 키의 env(local/prod)를 꺼내는 요청 속성 이름 */
    public static final String ENV_ATTRIBUTE = "aiops.env";

    private final ApiKeyResolver apiKeyResolver;
    private final SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();

    public ApiKeyAuthFilter(ApiKeyResolver apiKeyResolver) {
        this.apiKeyResolver = apiKeyResolver;
    }

    /** 체인이 /v1/**에만 걸지만, 다른 곳에 잘못 끼워져도 키를 보지 않게 한 번 더 막는다 */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ResolvedKey key;
        try {
            key = apiKeyResolver.resolve(request.getHeader(ProtocolConstants.HEADER_API_KEY));
        } catch (InvalidApiKeyException e) {
            // 키 없음·없는 키·폐기 키를 구분하지 않는다 (IngestExceptionHandler와 같은 응답)
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"invalid_api_key\"}");
            return;
        }

        try {
            SecurityContext context = holder.createEmptyContext();
            context.setAuthentication(new ApiKeyAuthentication(key.tenantId(), key.env()));
            holder.setContext(context);
            TenantContext.set(key.tenantId());
            request.setAttribute(ENV_ATTRIBUTE, key.env());
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            holder.clearContext();
        }
    }
}
