package dev.aiops.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

/** 로그인 후 돌아갈 경로 검사 (AI-106). DB 없이 */
class LoginNextTest {

    private final LoginNext filter = new LoginNext();

    @Test
    void 같은_도메인_경로만_허용한다() {
        assertThat(LoginNext.isSafe("/invite/abc_DEF-123")).isTrue();
        assertThat(LoginNext.isSafe("/teams/3/settings?first=1")).isTrue();
        assertThat(LoginNext.isSafe("/")).isTrue();
    }

    @Test
    void 외부로_나가는_값은_거부한다() {
        assertThat(LoginNext.isSafe(null)).isFalse();
        assertThat(LoginNext.isSafe("")).isFalse();
        assertThat(LoginNext.isSafe("https://evil.com")).isFalse();
        assertThat(LoginNext.isSafe("evil.com")).isFalse();
        assertThat(LoginNext.isSafe("javascript:alert(1)")).isFalse();
        assertThat(LoginNext.isSafe("//evil.com")).isFalse();
        assertThat(LoginNext.isSafe("/\\evil.com")).isFalse();
        assertThat(LoginNext.isSafe("/a\\b")).isFalse();
        assertThat(LoginNext.isSafe("/invite/x\r\nSet-Cookie: a=b")).isFalse();
        assertThat(LoginNext.isSafe("/" + "a".repeat(600))).isFalse();
    }

    private static MockHttpServletRequest authorization(String next) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/oauth2/authorization/github");
        if (next != null) {
            req.setParameter("next", next);
        }
        return req;
    }

    @Test
    void 로그인_시작에서_안전한_next를_세션에_둔다() throws Exception {
        MockHttpServletRequest req = authorization("/invite/abc");
        filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(req.getSession().getAttribute(LoginNext.SESSION_ATTR)).isEqualTo("/invite/abc");
    }

    @Test
    void 안전하지_않거나_없으면_예전_값도_지운다() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(LoginNext.SESSION_ATTR, "/invite/old");

        MockHttpServletRequest evil = authorization("https://evil.com");
        evil.setSession(session);
        filter.doFilter(evil, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(session.getAttribute(LoginNext.SESSION_ATTR)).isNull();

        session.setAttribute(LoginNext.SESSION_ATTR, "/invite/old");
        MockHttpServletRequest none = authorization(null);
        none.setSession(session);
        filter.doFilter(none, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(session.getAttribute(LoginNext.SESSION_ATTR)).isNull();
    }

    @Test
    void 로그인_시작이_아닌_요청은_건드리지_않는다() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/me");
        req.setParameter("next", "/invite/abc");
        filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(req.getSession(false)).isNull();
    }

    @Test
    void pop은_꺼내고_지우며_없거나_이상하면_루트() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        assertThat(LoginNext.pop(req)).isEqualTo("/");                 // 세션 없음

        req.getSession().setAttribute(LoginNext.SESSION_ATTR, "/invite/abc");
        assertThat(LoginNext.pop(req)).isEqualTo("/invite/abc");
        assertThat(req.getSession().getAttribute(LoginNext.SESSION_ATTR)).isNull();
        assertThat(LoginNext.pop(req)).isEqualTo("/");                 // 한 번만

        req.getSession().setAttribute(LoginNext.SESSION_ATTR, "//evil.com");   // 혹시 세션에 이상한 값이 있어도
        assertThat(LoginNext.pop(req)).isEqualTo("/");
    }
}
