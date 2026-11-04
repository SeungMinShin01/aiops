package dev.aiops.server.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.savedrequest.NullRequestCache;

/**
 * 보안 체인 (12단계). 경로로 나눈다 (D-021).
 * - sessionChain: 사람 입구 (/api/**, OAuth2 로그인). AI-78은 로그인·로그아웃·401까지, 쿠키·CSRF는 AI-79
 * - apiKeyChain(/v1/**): AI-79
 * /_test/**, /health는 어느 체인에도 넣지 않는다.
 * 웹 서버 없이 뜨는 테스트(WebEnvironment.NONE)에는 HttpSecurity가 없으므로 서블릿 웹일 때만 켠다.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

    @Bean
    @Order(2)
    SecurityFilterChain sessionChain(HttpSecurity http, GithubLoginSuccessHandler successHandler,
            GithubLoginFailureHandler failureHandler) throws Exception {
        http.securityMatcher("/api/**", "/oauth2/**", "/login/**", "/logout")
                .oauth2Login(o -> o
                        .loginPage("/login") // 로그인 화면은 콘솔이 그린다 (Spring 기본 로그인 페이지 생성 끔)
                        .successHandler(successHandler)
                        .failureHandler(failureHandler))
                .logout(l -> l
                        .logoutUrl("/api/logout")
                        .logoutSuccessHandler((req, res, auth) -> res.setStatus(204)))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/oauth2/**", "/login/**").permitAll()
                        .anyRequest().authenticated())
                // 미인증 /api 요청을 세션에 저장하지 않는다 (401만 주고 끝)
                .requestCache(r -> r.requestCache(new NullRequestCache()))
                // 로그인 페이지 redirect가 아니라 401. 콘솔이 401을 보고 로그인 화면으로 보낸다
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> res.sendError(401)));
        return http.build();
    }
}
