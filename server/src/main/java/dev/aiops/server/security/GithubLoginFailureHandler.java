package dev.aiops.server.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** GitHub 로그인 실패 (AI-78). 콘솔의 실패 화면으로 보낸다 (17단계) */
@Component
public class GithubLoginFailureHandler implements AuthenticationFailureHandler {

    static final String FAILURE_URL = "/login?error=github";

    private static final Logger log = LoggerFactory.getLogger(GithubLoginFailureHandler.class);

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        log.warn("github login failed: {}", exception.getClass().getSimpleName()); // 메시지·code는 남기지 않는다
        response.sendRedirect(FAILURE_URL);
    }
}
