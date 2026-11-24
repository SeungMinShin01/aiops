package dev.aiops.server.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

import java.util.function.Supplier;

/**
 * SPA용 CSRF 처리 (AI-79, Spring Security 문서의 "Single-Page Applications" 예제).
 * - 콘솔은 XSRF-TOKEN 쿠키 값을 그대로 X-XSRF-TOKEN 헤더로 되돌린다 → 헤더가 있으면 원문 비교
 * - 서버가 그린 폼 파라미터(_csrf)라면 BREACH 방어용 XOR 비교
 * - 토큰은 원래 쓸 때까지 미뤄지는데, 그러면 첫 GET에 쿠키가 안 나간다 → handle에서 매번 로드해 쿠키를 발급
 */
public final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {

    private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();
    private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
        xor.handle(request, response, csrfToken);
        csrfToken.get(); // 첫 GET에서 XSRF-TOKEN 쿠키 발급
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        String header = request.getHeader(csrfToken.getHeaderName());
        return (StringUtils.hasText(header) ? plain : xor).resolveCsrfTokenValue(request, csrfToken);
    }
}
