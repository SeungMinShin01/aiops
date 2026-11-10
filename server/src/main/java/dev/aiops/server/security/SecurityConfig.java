package dev.aiops.server.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;

/**
 * 보안 체인 2개 (12단계). 경로로 나눈다 (D-021). 한 체인 안에서 "헤더가 있으면 키, 없으면 세션"으로 분기하지 않는다.
 * - apiKeyChain  (/v1/**)  : 기계 입구. API 키, STATELESS, CSRF 없음 (AI-79)
 * - sessionChain (/api/**…): 사람 입구. GitHub 로그인 세션 + CSRF 쿠키 (AI-78·79)
 * /health, /_test/**는 어느 체인에도 넣지 않는다 (Caddy 헬스체크, 테스트 전용).
 * 웹 서버 없이 뜨는 테스트(WebEnvironment.NONE)에는 HttpSecurity가 없으므로 서블릿 웹일 때만 켠다.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain apiKeyChain(HttpSecurity http, ApiKeyResolver apiKeyResolver) throws Exception {
        http.securityMatcher("/v1/**")
                .csrf(c -> c.disable())
                // Starter 요청마다 세션이 생기면 메모리가 샌다
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(r -> r.requestCache(new NullRequestCache()))
                .addFilterBefore(new ApiKeyAuthFilter(apiKeyResolver), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> res.sendError(401)));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain sessionChain(HttpSecurity http, GithubLoginSuccessHandler successHandler,
            GithubLoginFailureHandler failureHandler) throws Exception {
        http.securityMatcher("/api/**", "/oauth2/**", "/login/**", "/logout")
                // 세션 쿠키 방식은 CSRF 방어 필수. 콘솔이 XSRF-TOKEN 쿠키를 읽어 X-XSRF-TOKEN 헤더로 되돌린다
                .csrf(c -> c
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
                .oauth2Login(o -> o
                        .loginPage("/login") // 로그인 화면은 콘솔이 그린다 (Spring 기본 로그인 페이지 생성 끔)
                        .successHandler(successHandler)
                        .failureHandler(failureHandler))
                .logout(l -> l
                        .logoutUrl("/api/logout") // POST + CSRF 헤더 필요. 세션 무효화 + 세션 쿠키 삭제
                        .deleteCookies("AIOPS_SESSION")
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
