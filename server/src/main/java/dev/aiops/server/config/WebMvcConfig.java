package dev.aiops.server.config;

import dev.aiops.server.security.AdminGuard;
import dev.aiops.server.security.MembershipGuard;
import dev.aiops.server.security.RequireOwnerInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 인가 인터셉터 등록 (12단계). 등록 순서 = 실행 순서.
 * - /api/teams/**: MembershipGuard → RequireOwnerInterceptor (순서가 바뀌면 역할 속성이 없어 항상 403)
 * - /api/admin/**: AdminGuard (13단계 접근 기록 인터셉터도 같은 패턴을 쓴다)
 * /api/teams/{teamId}/**는 MembershipGuard를 거치지 않으면 만들 수 없다 (규칙 12).
 * 팀 API는 이 접두사 아래에만 만든다 (/api/team/, /api/t/ 같은 다른 접두사 금지).
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final MembershipGuard membershipGuard;
    private final RequireOwnerInterceptor requireOwnerInterceptor;
    private final AdminGuard adminGuard;

    public WebMvcConfig(MembershipGuard membershipGuard, RequireOwnerInterceptor requireOwnerInterceptor,
            AdminGuard adminGuard) {
        this.membershipGuard = membershipGuard;
        this.requireOwnerInterceptor = requireOwnerInterceptor;
        this.adminGuard = adminGuard;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(membershipGuard).addPathPatterns("/api/teams/**");
        registry.addInterceptor(requireOwnerInterceptor).addPathPatterns("/api/teams/**");
        registry.addInterceptor(adminGuard).addPathPatterns("/api/admin/**");
    }
}
