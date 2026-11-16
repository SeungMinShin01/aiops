package dev.aiops.server.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import dev.aiops.server.support.PostgresContainerSupport;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** 실제 보안 체인에서 /oauth2/authorization/github?next= 가 GitHub로 가기 전에 세션에 남는지 (AI-106) */
@SpringBootTest
class LoginNextFlowTest extends PostgresContainerSupport {

    @Autowired WebApplicationContext context;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private MvcResult start(String next) throws Exception {
        return mvc.perform(get("/oauth2/authorization/github").param("next", next)).andReturn();
    }

    @Test
    void 안전한_next는_세션에_두고_GitHub로_보낸다() throws Exception {
        MvcResult result = start("/invite/abc");

        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        assertThat(result.getResponse().getRedirectedUrl()).startsWith("https://github.com/login/oauth/authorize");
        HttpSession session = result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        assertThat(session.getAttribute(LoginNext.SESSION_ATTR)).isEqualTo("/invite/abc");
    }

    @Test
    void 외부_주소_next는_세션에_남기지_않는다() throws Exception {
        for (String evil : new String[] {"https://evil.com", "//evil.com", "/\\evil.com"}) {
            MvcResult result = start(evil);

            assertThat(result.getResponse().getStatus()).isEqualTo(302);   // 로그인 자체는 진행
            HttpSession session = result.getRequest().getSession(false);
            if (session != null) {
                assertThat(session.getAttribute(LoginNext.SESSION_ATTR)).as(evil).isNull();
            }
        }
    }
}
