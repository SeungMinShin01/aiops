package dev.aiops.server.security;

import dev.aiops.server.model.entity.MemberRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * 조장 전용 검사 (AI-81, 3-5). @RequireOwner가 붙은 메서드(또는 클래스)는 OWNER만 통과, 아니면 403.
 * 역할은 MembershipGuard가 요청 속성(aiops.role)에 넣은 값 — 그래서 반드시 가드 다음에 등록한다 (WebMvcConfig).
 * 팀 역할을 Spring Security 권한(GrantedAuthority)에 넣지 않는다: 역할은 팀마다 다르다.
 */
@Component
public class RequireOwnerInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!(handler instanceof HandlerMethod method) || !requiresOwner(method)) {
            return true;
        }
        if (request.getAttribute(MembershipGuard.ROLE_ATTRIBUTE) != MemberRole.OWNER) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return false;
        }
        return true;
    }

    private static boolean requiresOwner(HandlerMethod method) {
        return method.hasMethodAnnotation(RequireOwner.class)
                || AnnotatedElementUtils.hasAnnotation(method.getBeanType(), RequireOwner.class);
    }
}
