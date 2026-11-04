package dev.aiops.server.security;

import dev.aiops.server.model.entity.AppUserEntity;
import dev.aiops.server.service.AppUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** GitHub 로그인 성공 처리 (AI-78). DB 없이: upsert는 가짜, 세션 교체만 본다 */
class GithubLoginSuccessHandlerTest {

    private final AppUserService service = mock(AppUserService.class);
    private final GithubLoginSuccessHandler handler = new GithubLoginSuccessHandler(service);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static OAuth2AuthenticationToken githubToken(Map<String, Object> attributes) {
        return new OAuth2AuthenticationToken(new DefaultOAuth2User(List.of(), attributes, "login"), List.of(), "github");
    }

    @Test
    void upsert_후_principal을_AppUserPrincipal로_바꾸고_루트로_보낸다() throws Exception {
        AppUserEntity saved = new AppUserEntity(256240676L, "hanwooya361", "조현우");
        ReflectionTestUtils.setField(saved, "id", 7L);
        when(service.upsertFromGithub(256240676L, "hanwooya361", "조현우")).thenReturn(saved);

        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(req, res,
                githubToken(Map.of("id", 256240676, "login", "hanwooya361", "name", "조현우")));

        AppUserPrincipal expected = new AppUserPrincipal(7L, 256240676L, "hanwooya361");
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(expected);

        SecurityContext inSession = (SecurityContext) req.getSession()
                .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(inSession.getAuthentication().getPrincipal()).as("세션에도 교체본").isEqualTo(expected);
        assertThat(CurrentUser.get()).isEqualTo(expected);
        assertThat(res.getRedirectedUrl()).isEqualTo("/");
    }

    @Test
    void id가_없으면_실패_화면으로_보내고_저장하지_않는다() throws Exception {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("login", "nobody");

        MockHttpServletResponse res = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(new MockHttpServletRequest(), res, githubToken(attributes));

        verify(service, never()).upsertFromGithub(anyLong(), any(), any());
        assertThat(res.getRedirectedUrl()).isEqualTo("/login?error=github");
        assertThat(CurrentUser.find()).isEmpty();
    }
}
