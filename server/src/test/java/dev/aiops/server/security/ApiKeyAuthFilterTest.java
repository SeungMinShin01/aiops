package dev.aiops.server.security;

import dev.aiops.protocol.ProtocolConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ApiKeyAuthFilter (AI-79, TenantFilterTest를 옮김). DB 없이: 가짜 ApiKeyResolver + 확인용 컨트롤러 */
class ApiKeyAuthFilterTest {

    static final AtomicReference<Long> seenTenant = new AtomicReference<>();
    static final AtomicReference<Authentication> seenAuth = new AtomicReference<>();

    @RestController
    static class ProbeController {
        @GetMapping("/v1/probe")
        String probe(@RequestAttribute(ApiKeyAuthFilter.ENV_ATTRIBUTE) String env) {
            seenTenant.set(TenantContext.require());
            seenAuth.set(SecurityContextHolder.getContext().getAuthentication());
            return TenantContext.require() + ":" + env;
        }

        @GetMapping("/v1/boom")
        String boom() {
            throw new IllegalStateException("컨트롤러에서 예외");
        }

        @GetMapping("/health-probe")
        String health() {
            return TenantContext.get().map(String::valueOf).orElse("none");
        }
    }

    ApiKeyResolver resolver = mock(ApiKeyResolver.class);
    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        seenTenant.set(null);
        seenAuth.set(null);
        when(resolver.resolve("good-key")).thenReturn(new ResolvedKey(42L, "local", 1L));
        when(resolver.resolve("bad-key")).thenThrow(new InvalidApiKeyException("invalid api key"));
        when(resolver.resolve(null)).thenThrow(new InvalidApiKeyException("invalid api key"));
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .addFilters(new ApiKeyAuthFilter(resolver))
                .build();
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void 정상_키면_팀_env_인증이_설정되고_요청_후에는_비어_있다() throws Exception {
        mockMvc.perform(get("/v1/probe").header(ProtocolConstants.HEADER_API_KEY, "good-key"))
                .andExpect(status().isOk())
                .andExpect(content().string("42:local"));

        assertThat(seenTenant.get()).isEqualTo(42L);
        assertThat(seenAuth.get()).isInstanceOf(ApiKeyAuthentication.class);
        assertThat(((ApiKeyAuthentication) seenAuth.get()).tenantId()).isEqualTo(42L);
        assertThat(((ApiKeyAuthentication) seenAuth.get()).env()).isEqualTo("local");
        assertThat(seenAuth.get().isAuthenticated()).isTrue();

        // MockMvc는 같은 스레드에서 돈다 → finally clear 확인
        assertThat(TenantContext.get()).isEmpty();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void 잘못된_키는_401_컨트롤러에_닿지_않고_비어_있다() throws Exception {
        mockMvc.perform(get("/v1/probe").header(ProtocolConstants.HEADER_API_KEY, "bad-key"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_api_key"));

        assertThat(seenTenant.get()).isNull();
        assertThat(TenantContext.get()).isEmpty();
    }

    @Test
    void 키_헤더가_없으면_401() throws Exception {
        mockMvc.perform(get("/v1/probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_api_key"));

        assertThat(TenantContext.get()).isEmpty();
    }

    @Test
    void 컨트롤러에서_예외가_나도_요청_후에는_비어_있다() {
        assertThatThrownBy(() -> mockMvc.perform(get("/v1/boom").header(ProtocolConstants.HEADER_API_KEY, "good-key")))
                .hasRootCauseInstanceOf(IllegalStateException.class);

        assertThat(TenantContext.get()).isEmpty();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void v1이_아닌_경로는_키를_보지_않고_팀도_설정하지_않는다() throws Exception {
        ApiKeyResolver untouched = mock(ApiKeyResolver.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .addFilters(new ApiKeyAuthFilter(untouched))
                .build();

        mvc.perform(get("/health-probe"))
                .andExpect(status().isOk())
                .andExpect(content().string("none"));

        verifyNoInteractions(untouched);
    }

    @Test
    void 이전_요청의_팀을_물려받지_않는다() throws Exception {
        mockMvc.perform(get("/v1/probe").header(ProtocolConstants.HEADER_API_KEY, "good-key"))
                .andExpect(status().isOk());

        // 같은 스레드에서 다음 요청이 잘못된 키로 오면 앞 팀(42)이 남아 있으면 안 된다
        mockMvc.perform(get("/v1/probe").header(ProtocolConstants.HEADER_API_KEY, "bad-key"))
                .andExpect(status().isUnauthorized());
        assertThat(TenantContext.get()).isEmpty();
    }
}
