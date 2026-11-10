package dev.aiops.server.security;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * 세션의 로그인 사용자(AppUserPrincipal)를 꺼낸다 (AI-78).
 * 인증 객체의 타입이 아니라 principal 타입으로 판단한다. 테스트 로그인(UsernamePasswordAuthenticationToken)과
 * 실제 GitHub 로그인(성공 핸들러가 principal을 AppUserPrincipal로 교체) 모두 같은 방식으로 꺼내진다.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static Optional<AppUserPrincipal> find() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppUserPrincipal principal) {
            return Optional.of(principal);
        }
        return Optional.empty();
    }

    /** /api/** 체인을 통과한 요청에서만 부른다. 없으면 인증 예외 → 체인이 401로 바꾼다 */
    public static AppUserPrincipal get() {
        return find().orElseThrow(() -> new AuthenticationCredentialsNotFoundException("no login user"));
    }
}
