package dev.aiops.server.client;

import dev.aiops.server.config.DiscordClientConfig;
import dev.aiops.server.config.DiscordProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DiscordOAuthClientTest {

    static final String API = DiscordClientConfig.BASE_URL;
    // 가짜 값 (규칙 10)
    static final DiscordProperties PROPS = new DiscordProperties("111", "fake-bot-token", "client-1",
            "fake-client-secret", "http://localhost:8080/api/discord/callback");

    MockRestServiceServer server;
    DiscordOAuthClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(API);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new DiscordOAuthClient(builder.build(), PROPS);
    }

    @Test
    void exchange는_form으로_code를_보내고_access_token을_돌려준다() {
        MultiValueMap<String, String> expected = new LinkedMultiValueMap<>();
        expected.add("client_id", "client-1");
        expected.add("client_secret", "fake-client-secret");
        expected.add("grant_type", "authorization_code");
        expected.add("code", "the-code");
        expected.add("redirect_uri", "http://localhost:8080/api/discord/callback");
        server.expect(requestTo(API + "/oauth2/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formData(expected))
                .andRespond(withSuccess("{\"access_token\":\"user-token-123\",\"token_type\":\"Bearer\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.exchange("the-code")).isEqualTo("user-token-123");
        server.verify();
    }

    @Test
    void me는_사용자_Bearer로_부르고_봇_토큰은_쓰지_않는다() {
        server.expect(requestTo(API + "/users/@me"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer user-token-123"))
                .andRespond(withSuccess("{\"id\":\"555\",\"username\":\"hw\"}", MediaType.APPLICATION_JSON));

        assertThat(client.me("user-token-123")).isEqualTo("555");
        server.verify();
    }

    @Test
    void 실패하면_status와_호출_이름만_담은_예외() {
        server.expect(requestTo(API + "/oauth2/token"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_grant\",\"echo\":\"fake-client-secret\"}"));

        assertThatThrownBy(() -> client.exchange("bad-code"))
                .isInstanceOfSatisfying(DiscordApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(400);
                    assertThat(e.getOperation()).isEqualTo("oauthExchange");
                    assertThat(e.getMessage()).doesNotContain("fake-client-secret").doesNotContain("bad-code");
                    assertThat(e.getCause()).isNull();
                });
    }

    @Test
    void me가_401이면_예외에_토큰이_없다() {
        server.expect(requestTo(API + "/users/@me")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.me("user-token-123"))
                .isInstanceOfSatisfying(DiscordApiException.class,
                        e -> assertThat(e.getMessage()).contains("oauthMe").doesNotContain("user-token-123"));
    }
}
