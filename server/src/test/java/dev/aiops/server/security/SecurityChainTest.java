package dev.aiops.server.security;

import dev.aiops.protocol.ProtocolConstants;
import dev.aiops.server.model.entity.ApiKeyEntity;
import dev.aiops.server.model.entity.TenantEntity;
import dev.aiops.server.model.repository.ApiKeyRepository;
import dev.aiops.server.model.repository.TenantRepository;
import dev.aiops.server.support.PostgresContainerSupport;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 보안 체인 2개 (AI-79). 진짜 보안 필터 체인(springSecurity) + 진짜 DB.
 * /v1은 키·STATELESS, /api는 세션·CSRF. 두 입구가 섞이지 않는지 본다.
 */
@SpringBootTest
class SecurityChainTest extends PostgresContainerSupport {

    private static final String RAW_KEY = "security-chain-test-key";
    private static final String EMPTY_BATCH =
            "{\"env\":\"local\",\"app\":{\"name\":\"t\",\"hostHash\":\"h1\"},\"events\":[],\"droppedCount\":0}";

    @Autowired WebApplicationContext context;
    @Autowired TenantRepository tenants;
    @Autowired ApiKeyRepository apiKeys;
    @Autowired ApiKeyResolver resolver;

    MockMvc mvc;
    long userId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        JdbcTemplate owner = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), OWNER_USER, OWNER_PASSWORD));
        owner.execute("TRUNCATE membership, invitation, admin_access_log, app_user, cohort, error_event, error_group, "
                + "app_snapshot, ingest_stat, heartbeat_last, api_key, tenant RESTART IDENTITY CASCADE");
        resolver.evict(ApiKeyHasher.sha256Hex(RAW_KEY));

        Long tenantId = tenants.save(new TenantEntity("체인 테스트 팀")).getId();
        apiKeys.save(new ApiKeyEntity(tenantId, ApiKeyHasher.sha256Hex(RAW_KEY), "local"));
        userId = owner.queryForObject(
                "INSERT INTO app_user (github_id, login) VALUES (9001, 'hanwoo') RETURNING id", Long.class);
    }

    /** 로그인된 세션 (기본 저장소 HttpSessionSecurityContextRepository와 같은 자리에 넣는다) */
    private MockHttpSession loggedIn() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(
                        new AppUserPrincipal(userId, 9001L, "hanwoo"), null, List.of())));
        return session;
    }

    // ---- /v1 ----

    @Test
    void v1_키_없으면_401이고_세션을_만들지_않는다() throws Exception {
        MvcResult result = mvc.perform(post(ProtocolConstants.PATH_EVENTS)
                        .contentType(MediaType.APPLICATION_JSON).content(EMPTY_BATCH))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
        assertThat(result.getRequest().getSession(false)).isNull();
    }

    @Test
    void v1_유효한_키면_202이고_세션을_만들지_않는다() throws Exception {
        MvcResult result = mvc.perform(post(ProtocolConstants.PATH_EVENTS)
                        .header(ProtocolConstants.HEADER_API_KEY, RAW_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(EMPTY_BATCH))
                .andExpect(status().isAccepted())
                .andReturn();

        assertThat(result.getResponse().getHeaders("Set-Cookie")).as("STATELESS").isEmpty();
        assertThat(result.getRequest().getSession(false)).isNull();
    }

    @Test
    void v1에_세션_쿠키만_보내면_401() throws Exception {
        mvc.perform(post(ProtocolConstants.PATH_EVENTS).session(loggedIn())
                        .contentType(MediaType.APPLICATION_JSON).content(EMPTY_BATCH))
                .andExpect(status().isUnauthorized());
    }

    // ---- /api ----

    @Test
    void api_세션_없으면_401이고_redirect가_아니다() throws Exception {
        MvcResult result = mvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertThat(result.getResponse().getHeader("Location")).isNull();
    }

    @Test
    void api에_키만_보내면_401() throws Exception {
        mvc.perform(get("/api/me").header(ProtocolConstants.HEADER_API_KEY, RAW_KEY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 세션_있고_CSRF_헤더_없는_POST는_403() throws Exception {
        MockHttpSession session = loggedIn();

        mvc.perform(post("/api/logout").session(session))
                .andExpect(status().isForbidden());

        // 막혔으니 세션은 그대로 살아 있다
        mvc.perform(get("/api/me").session(session)).andExpect(status().isOk());
    }

    @Test
    void 첫_GET에서_XSRF_쿠키를_받고_헤더로_되돌리면_통과() throws Exception {
        MockHttpSession session = loggedIn();

        MvcResult first = mvc.perform(get("/api/me").session(session))
                .andExpect(status().isOk())
                .andReturn();
        Cookie xsrf = first.getResponse().getCookie("XSRF-TOKEN");
        assertThat(xsrf).as("첫 GET에서 발급").isNotNull();
        assertThat(xsrf.isHttpOnly()).as("콘솔 JS가 읽어야 한다").isFalse();

        mvc.perform(post("/api/logout").session(session)
                        .cookie(xsrf)
                        .header("X-XSRF-TOKEN", xsrf.getValue()))
                .andExpect(status().isNoContent());

        assertThat(session.isInvalid()).as("로그아웃 = 세션 무효화").isTrue();
    }

    @Test
    void 헤더_값이_쿠키와_다르면_403() throws Exception {
        MockHttpSession session = loggedIn();
        Cookie xsrf = mvc.perform(get("/api/me").session(session)).andReturn().getResponse().getCookie("XSRF-TOKEN");

        mvc.perform(post("/api/logout").session(session)
                        .cookie(xsrf)
                        .header("X-XSRF-TOKEN", "forged"))
                .andExpect(status().isForbidden());
    }
}
