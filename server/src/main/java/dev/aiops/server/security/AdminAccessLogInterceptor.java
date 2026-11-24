package dev.aiops.server.security;

import dev.aiops.server.service.AdminAccessLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 관리자 API 호출을 전부 admin_access_log 에 남긴다 (AI-87, D-024, 규칙 15).
 * /api/admin/** 에 AdminGuard 다음 순서로 등록한다. 컨트롤러·서비스에서 직접 기록하지 않는다 (이 클래스 한 곳만).
 * <p>
 * AdminGuard 가 403 으로 막은 호출은 이 인터셉터까지 오지 않아서 기록되지 않는다 (관리자의 조회만 기록).
 * 요청·응답 본문은 기록하지 않는다 — 초대 토큰 원문이 응답에 있다 (규칙 10).
 */
@Component
public class AdminAccessLogInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AdminAccessLogInterceptor.class);

    private final AdminAccessLogService accessLogService;

    public AdminAccessLogInterceptor(AdminAccessLogService accessLogService) {
        this.accessLogService = accessLogService;
    }

    // AI-87 : afterCompletion = 컨트롤러에서 예외가 나도 호출되고, 응답 상태를 알 수 있어서 여기서 기록
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
            Exception ex) {
        try {
            AppUserPrincipal admin = CurrentUser.get();
            Long tenantId = teamId(request);

            // AI-87 : 실제 주소(/api/admin/teams/7/suspend)가 아니라 맞은 패턴(/api/admin/teams/{teamId}/suspend)을 기록
            String pattern = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            if (pattern == null) {
                pattern = request.getRequestURI();   // 맞는 컨트롤러가 없을 때(404)
            }

            // AI-87 : 처리 못 한 예외로 끝나면 이 시점의 response 상태는 아직 200 일 수 있다 (500 은 나중에 컨테이너가 정함) → 500 으로 기록
            int status = response.getStatus();
            if (ex != null && status < 400) {
                status = 500;
            }

            accessLogService.record(admin.appUserId(), tenantId, request.getMethod() + " " + pattern + " " + status);
        } catch (RuntimeException e) {
            // AI-87 : 기록 실패는 로그만. 응답은 이미 나갔다
            log.error("관리자 접근 기록 실패", e);
        }
    }

    // AI-87 : 경로 변수 teamId 가 있으면 그 값, 없으면 null
    @SuppressWarnings("unchecked")
    private Long teamId(HttpServletRequest request) {
        Map<String, String> variables =
                (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (variables == null || variables.get("teamId") == null) {
            return null;
        }
        try {
            return Long.valueOf(variables.get("teamId"));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}