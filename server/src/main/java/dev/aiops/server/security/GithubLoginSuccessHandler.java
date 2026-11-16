package dev.aiops.server.security;

import dev.aiops.server.model.entity.AppUserEntity;
import dev.aiops.server.service.AppUserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * GitHub 로그인 성공 (AI-78).
 * 1. GitHub 속성(id, login, name)으로 app_user upsert
 * 2. 세션의 인증을 우리 AppUserPrincipal로 교체 → 이후 코드는 GitHub 속성이 아니라 우리 id를 쓴다
 * 3. / (콘솔 루트)로 redirect
 * GitHub access token은 여기서 꺼내지도 저장하지도 않는다.
 */
@Component
public class GithubLoginSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(GithubLoginSuccessHandler.class);

    private final AppUserService appUserService;
    private final SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();
    private final SecurityContextRepository repository = new HttpSessionSecurityContextRepository(); // 기본 저장소 그대로

    public GithubLoginSuccessHandler(AppUserService appUserService) {
        this.appUserService = appUserService;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException {
        if (!(authentication.getPrincipal() instanceof OAuth2User github)
                || !(github.getAttribute("id") instanceof Number id)
                || !(github.getAttribute("login") instanceof String login)) {
            log.warn("github login without id/login");
            holder.clearContext();
            request.getSession().invalidate();
            response.sendRedirect(GithubLoginFailureHandler.FAILURE_URL);
            return;
        }
        String name = github.getAttribute("name");

        AppUserEntity user = appUserService.upsertFromGithub(id.longValue(), login, name);
        AppUserPrincipal principal = new AppUserPrincipal(user.getId(), user.getGithubId(), user.getLogin());

        SecurityContext context = holder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
        holder.setContext(context);
        repository.saveContext(context, request, response);

        response.sendRedirect("/");
    }
}
