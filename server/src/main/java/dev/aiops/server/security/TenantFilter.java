package dev.aiops.server.security;

import dev.aiops.protocol.ProtocolConstants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * /v1/** 요청: 키로 찾은 팀을 TenantContext에 넣고, 요청이 끝나면 반드시 비운다 (AI-70).
 * <p>
 * 팀은 키로만 정해진다 (⑫). 요청 본문·파라미터의 팀 정보는 믿지 않는다.
 * set한 곳이 finally에서 clear — 예외가 나도 다음 요청이 이전 팀을 물려받지 않는다 (v0.2 규칙 10).
 * 12단계에서 ApiKeyAuthFilter(보안 체인)로 흡수된다. 그때 옮기기 쉽게 "resolve + set/clear"만 둔다.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class TenantFilter extends OncePerRequestFilter {

    /** 컨트롤러가 키의 env(local/prod)를 꺼내는 요청 속성 이름 */
    public static final String ENV_ATTRIBUTE = "aiops.env";

    private final ApiKeyResolver apiKeyResolver;

    public TenantFilter(ApiKeyResolver apiKeyResolver) {
        this.apiKeyResolver = apiKeyResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            ResolvedKey key = apiKeyResolver.resolve(request.getHeader(ProtocolConstants.HEADER_API_KEY));
            TenantContext.set(key.tenantId());
            request.setAttribute(ENV_ATTRIBUTE, key.env());
            chain.doFilter(request, response);
        } catch (InvalidApiKeyException e) {
            // 없는 키·폐기 키를 구분하지 않는다 (IngestExceptionHandler와 같은 응답)
            if (!response.isCommitted()) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write("{\"error\":\"invalid_api_key\"}");
            }
        } finally {
            TenantContext.clear();
        }
    }
}
