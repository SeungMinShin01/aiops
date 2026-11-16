package dev.aiops.server.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 로그인 후 돌아갈 경로 (AI-106). 초대 링크로 들어온 사람이 로그인한 뒤 /invite/{token}으로 돌아오게 한다.
 * <ol>
 *   <li>콘솔이 GitHub 버튼 링크를 {@code /oauth2/authorization/github?next=/invite/abc}로 만든다</li>
 *   <li>이 필터가 GitHub로 보내기 직전에 next를 검사해서 세션에 둔다 (GitHub 왕복 동안 URL 쿼리는 사라지므로)</li>
 *   <li>로그인 성공 처리(GithubLoginSuccessHandler)가 세션에서 꺼내 그리로 redirect하고 지운다</li>
 * </ol>
 * 열린 리다이렉트 방지: 같은 도메인 경로(/로 시작)만 허용한다. {@code //evil.com}, {@code /\evil.com},
 * {@code https://...}, 줄바꿈이 섞인 값은 버린다 → 루트(/)로 간다.
 */
public class LoginNext extends OncePerRequestFilter {

    static final String SESSION_ATTR = "aiops.login.next";
    static final String PARAM = "next";
    private static final String AUTHORIZATION_PREFIX = "/oauth2/authorization/";
    private static final int MAX_LENGTH = 512;

    /** 같은 도메인 경로면 true. 판단 기준은 이 메서드 하나 (필터·성공 처리가 같이 쓴다) */
    public static boolean isSafe(String next) {
        if (next == null || next.isEmpty() || next.length() > MAX_LENGTH) {
            return false;
        }
        if (next.charAt(0) != '/') {
            return false;                                   // https://evil.com, evil.com, javascript:...
        }
        if (next.length() > 1 && (next.charAt(1) == '/' || next.charAt(1) == '\\')) {
            return false;                                   // //evil.com, /\evil.com (브라우저가 다른 호스트로 해석)
        }
        for (int i = 0; i < next.length(); i++) {
            char c = next.charAt(i);
            if (c == '\\' || c < 0x20 || c == 0x7f) {
                return false;                               // 역슬래시, 줄바꿈(헤더 주입) 등 제어 문자
            }
        }
        return true;
    }

    /** 세션에 둔 next를 꺼내고 지운다. 없거나 안전하지 않으면 "/" */
    static String pop(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return "/";
        }
        Object value = session.getAttribute(SESSION_ATTR);
        session.removeAttribute(SESSION_ATTR);
        return (value instanceof String next && isSafe(next)) ? next : "/";
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"GET".equals(request.getMethod()) || !request.getRequestURI().startsWith(AUTHORIZATION_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String next = request.getParameter(PARAM);
        if (isSafe(next)) {
            request.getSession(true).setAttribute(SESSION_ATTR, next);
        } else {
            // 새 로그인 시도인데 next가 없거나 안전하지 않으면 예전 값이 남아 엉뚱한 곳으로 가지 않게 지운다
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.removeAttribute(SESSION_ATTR);
            }
        }
        chain.doFilter(request, response);
    }
}
