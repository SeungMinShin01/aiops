package dev.aiops.server.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Optional;

/**
 * 관리자 경로 /api/admin/** (AI-81, A-06). 환경변수 GitHub id 목록에 있는 사람만 통과, 아니면 403.
 * 목록이 비어 있으면 관리자 없음 = 전부 403 (첫 로그인 사용자를 관리자로 만드는 편의 기능 없음).
 * 관리자 경로는 MembershipGuard를 타지 않으므로 TenantContext는 비어 있다 — 13단계 서비스가 팀마다 직접 설정한다 (D-082).
 * 관리자를 특정 팀의 OWNER로 취급하지 않는다.
 */
@Component
public class AdminGuard implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AdminGuard.class);

    private final AdminProperties adminProperties;

    public AdminGuard(AdminProperties adminProperties) {
        this.adminProperties = adminProperties;
        if (adminProperties.githubIds().isEmpty()) {
            log.warn("AIOPS_ADMIN_GITHUB_IDS가 비어 있음 — 관리자 없음, /api/admin/**는 전부 403");
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        Optional<AppUserPrincipal> user = CurrentUser.find(); // 세션 없으면 /api 체인이 이미 401
        if (user.isEmpty() || !adminProperties.isAdmin(user.get().githubId())) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return false;
        }
        return true;
    }
}
