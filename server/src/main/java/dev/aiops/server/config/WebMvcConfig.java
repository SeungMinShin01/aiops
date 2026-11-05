package dev.aiops.server.config;

import dev.aiops.server.security.MembershipGuard;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 인가 인터셉터 등록 (12단계). 등록 순서 = 실행 순서.
 * /api/teams/{teamId}/**는 MembershipGuard를 거치지 않으면 만들 수 없다 (규칙 12).
 * 팀 API는 이 접두사 아래에만 만든다 (/api/team/, /api/t/ 같은 다른 접두사 금지).
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final MembershipGuard membershipGuard;

    public WebMvcConfig(MembershipGuard membershipGuard) {
        this.membershipGuard = membershipGuard;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(membershipGuard).addPathPatterns("/api/teams/**");
    }
}
