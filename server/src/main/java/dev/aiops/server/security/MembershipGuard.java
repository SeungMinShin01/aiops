package dev.aiops.server.security;

import dev.aiops.server.model.entity.MembershipEntity;
import dev.aiops.server.model.entity.TenantStatus;
import dev.aiops.server.model.repository.MembershipRepository;
import dev.aiops.server.model.repository.TenantRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;
import java.util.Optional;

/**
 * 팀 접근 확인 (AI-80, A-04). /api/teams/{teamId}/**의 유일한 관문 — 이중 방어의 첫 번째, 두 번째는 RLS.
 * 1. URL 경로의 팀 번호만 쓴다 (쿠키·세션·헤더 X). 숫자가 아니면 404
 * 2. 요청마다 DB로 소속 확인 (세션에 캐시하지 않는다 → 내보낸 사람은 즉시 403)
 * 3. 정지된 팀이면 멤버라도 403 + {"reason":"SUSPENDED"}
 * 4. 통과하면 TenantContext = 팀, 요청 속성 aiops.role = OWNER/MEMBER (RequireOwnerInterceptor가 읽음)
 * 5. afterCompletion에서 항상 clear (postHandle은 예외 시 안 불려서 쓰지 않는다)
 * 관리자 예외는 없다. 관리자는 /api/admin/**로만 다른 팀을 본다 (D-024).
 */
@Component
public class MembershipGuard implements HandlerInterceptor {

    /** RequireOwnerInterceptor가 읽는 역할 속성 */
    public static final String ROLE_ATTRIBUTE = "aiops.role";

    private static final PathPattern TEAM_PATH = PathPatternParser.defaultInstance.parse("/api/teams/{teamId}/**");

    private final MembershipRepository memberships;
    private final TenantRepository tenants;

    public MembershipGuard(MembershipRepository memberships, TenantRepository tenants) {
        this.memberships = memberships;
        this.tenants = tenants;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        Optional<Long> teamId = teamIdOf(request);
        if (teamId.isEmpty()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return false;
        }

        AppUserPrincipal user = CurrentUser.get(); // 세션 없으면 /api 체인이 이미 401
        Optional<MembershipEntity> membership = memberships.findByUserIdAndTenantId(user.appUserId(), teamId.get());
        if (membership.isEmpty()) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return false;
        }

        boolean suspended = tenants.findById(teamId.get())
                .map(t -> t.getStatus() == TenantStatus.SUSPENDED)
                .orElse(true); // 팀이 없으면 통과시키지 않는다
        if (suspended) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"reason\":\"SUSPENDED\"}");
            return false;
        }

        TenantContext.set(teamId.get());
        request.setAttribute(ROLE_ATTRIBUTE, membership.get().getRole());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
            Exception ex) {
        TenantContext.clear(); // 예외·403 무관하게 (preHandle이 true였던 요청은 항상 여기로 온다)
    }

    /**
     * 팀 번호를 요청 경로에서 직접 읽는다. 컨트롤러 맵핑의 경로 변수를 쓰면 맵핑이 없는 경로
     * (/api/teams/{다른팀}/아무거나)에서 값이 없어 404가 되는데, 그러면 다른 팀 경로가 있는지 없는지가 드러난다.
     * 맵핑과 상관없이 가드가 먼저 403을 준다 (AuthorizationE2ETest 3번).
     */
    static Optional<Long> teamIdOf(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        PathPattern.PathMatchInfo match = TEAM_PATH.matchAndExtract(PathContainer.parsePath(path));
        if (match == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.valueOf(match.getUriVariables().get("teamId")));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
