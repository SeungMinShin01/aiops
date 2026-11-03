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
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DiscordApiClientTest {

    static final String API = DiscordClientConfig.BASE_URL;
    static final String GUILD = "111";
    // 가짜 값 (진짜 토큰·웹훅 아님, 규칙 10)
    static final DiscordProperties PROPS = new DiscordProperties(GUILD, "fake-bot-token", null, null, null);

    MockRestServiceServer server;
    DiscordApiClient client;
    List<Duration> sleeps = new ArrayList<>();

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(API);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new DiscordApiClient(builder.build(), PROPS, sleeps::add);
    }

    private static String json(String s) {
        return s;
    }

    @Test
    void createRole은_권한_0_멘션_불가로_역할을_만든다() {
        server.expect(requestTo(API + "/guilds/111/roles"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.name").value("team-shop"))
                .andExpect(jsonPath("$.permissions").value("0"))
                .andExpect(jsonPath("$.mentionable").value(false))
                .andRespond(withSuccess(json("{\"id\":\"900\"}"), MediaType.APPLICATION_JSON));

        assertThat(client.createRole("team-shop")).isEqualTo("900");
        server.verify();
    }

    @Test
    void createCategory는_everyone_거부_팀역할_허용_봇_허용_overwrite를_보낸다() {
        server.expect(once(), requestTo(API + "/users/@me"))
                .andRespond(withSuccess("{\"id\":\"777\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(API + "/guilds/111/channels"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.name").value("team-shop"))
                .andExpect(jsonPath("$.type").value(4))
                .andExpect(jsonPath("$.permission_overwrites[0].id").value(GUILD))
                .andExpect(jsonPath("$.permission_overwrites[0].type").value(0))
                .andExpect(jsonPath("$.permission_overwrites[0].deny").value("1024"))
                .andExpect(jsonPath("$.permission_overwrites[1].id").value("900"))
                .andExpect(jsonPath("$.permission_overwrites[1].type").value(0))
                .andExpect(jsonPath("$.permission_overwrites[1].allow").value("1024"))
                .andExpect(jsonPath("$.permission_overwrites[2].id").value("777"))
                .andExpect(jsonPath("$.permission_overwrites[2].type").value(1))
                .andExpect(jsonPath("$.permission_overwrites[2].allow").value("1024"))
                .andRespond(withSuccess("{\"id\":\"500\"}", MediaType.APPLICATION_JSON));

        assertThat(client.createCategory("team-shop", "900")).isEqualTo("500");
        server.verify();
    }

    @Test
    void 봇_id는_한_번만_조회하고_캐시한다() {
        server.expect(once(), requestTo(API + "/users/@me"))
                .andRespond(withSuccess("{\"id\":\"777\"}", MediaType.APPLICATION_JSON));

        assertThat(client.botUserId()).isEqualTo("777");
        assertThat(client.botUserId()).isEqualTo("777");
        server.verify();
    }

    @Test
    void createTextChannel은_카테고리_아래_권한_상속으로_만든다() {
        server.expect(requestTo(API + "/guilds/111/channels"))
                .andExpect(jsonPath("$.name").value("shop-local"))
                .andExpect(jsonPath("$.type").value(0))
                .andExpect(jsonPath("$.parent_id").value("500"))
                .andExpect(jsonPath("$.permission_overwrites").doesNotExist())
                .andRespond(withSuccess("{\"id\":\"600\"}", MediaType.APPLICATION_JSON));

        assertThat(client.createTextChannel("shop-local", "500")).isEqualTo("600");
        server.verify();
    }

    @Test
    void createWebhook은_응답의_url을_쓴다() {
        server.expect(requestTo(API + "/channels/600/webhooks"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.name").value("aiops"))
                .andRespond(withSuccess("{\"id\":\"42\",\"token\":\"tok\",\"url\":\"https://webhook.test/42/tok\"}",
                        MediaType.APPLICATION_JSON));

        WebhookInfo hook = client.createWebhook("600", "aiops");

        assertThat(hook.id()).isEqualTo("42");
        assertThat(hook.url()).isEqualTo("https://webhook.test/42/tok");
    }

    @Test
    void createWebhook은_url이_없으면_id와_token으로_조립한다() {
        server.expect(requestTo(API + "/channels/600/webhooks"))
                .andRespond(withSuccess("{\"id\":\"42\",\"token\":\"tok\"}", MediaType.APPLICATION_JSON));

        assertThat(client.createWebhook("600", "aiops").url())
                .isEqualTo("https://discord.com/api/webhooks/42/tok");
    }

    @Test
    void WebhookInfo_toString에는_url이_없다() {
        assertThat(new WebhookInfo("42", "https://webhook.test/42/secret-token").toString())
                .contains("42").doesNotContain("secret-token").doesNotContain("webhook.test");
    }

    @Test
    void deleteWebhook과_removeRole은_404를_성공으로_본다() {
        server.expect(requestTo(API + "/webhooks/42"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(API + "/guilds/111/members/u1/roles/900"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatCode(() -> {
            client.deleteWebhook("42");
            client.removeRole("u1", "900");
        }).doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void deleteChannel과_deleteRole은_경로대로_지운다() {
        server.expect(requestTo(API + "/channels/600")).andExpect(method(HttpMethod.DELETE)).andRespond(withNoContent());
        server.expect(requestTo(API + "/guilds/111/roles/900")).andExpect(method(HttpMethod.DELETE)).andRespond(withNoContent());

        client.deleteChannel("600");
        client.deleteRole("900");
        server.verify();
    }

    @Test
    void addMember는_201이면_ADDED_204면_ALREADY_MEMBER() {
        server.expect(requestTo(API + "/guilds/111/members/u1"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.access_token").value("user-oauth-token"))
                .andExpect(jsonPath("$.roles[0]").value("900"))
                .andRespond(withStatus(HttpStatus.CREATED));
        server.expect(requestTo(API + "/guilds/111/members/u2"))
                .andRespond(withNoContent());

        assertThat(client.addMember("u1", "user-oauth-token", List.of("900"))).isEqualTo(DiscordApiClient.AddMemberResult.ADDED);
        assertThat(client.addMember("u2", "user-oauth-token", List.of("900"))).isEqualTo(DiscordApiClient.AddMemberResult.ALREADY_MEMBER);
        server.verify();
    }

    @Test
    void addRole은_멤버에게_역할을_붙인다() {
        server.expect(requestTo(API + "/guilds/111/members/u1/roles/900"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withNoContent());

        client.addRole("u1", "900");
        server.verify();
    }

    @Test
    void 응답_429면_Retry_After만큼_기다렸다가_1회_재시도한다() {
        server.expect(requestTo(API + "/guilds/111/roles"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "2"));
        server.expect(requestTo(API + "/guilds/111/roles"))
                .andRespond(withSuccess("{\"id\":\"900\"}", MediaType.APPLICATION_JSON));

        assertThat(client.createRole("team-shop")).isEqualTo("900");
        assertThat(sleeps).containsExactly(Duration.ofSeconds(2));
        server.verify();
    }

    @Test
    void 재시도도_429면_DiscordApiException_더_재시도하지_않는다() {
        server.expect(requestTo(API + "/guilds/111/roles"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "1"));
        server.expect(requestTo(API + "/guilds/111/roles"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "1"));

        assertThatThrownBy(() -> client.createRole("team-shop"))
                .isInstanceOfSatisfying(DiscordApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(429);
                    assertThat(e.getOperation()).isEqualTo("createRole");
                });
        assertThat(sleeps).hasSize(1);
        server.verify(); // 세 번째 요청이 갔다면 기대하지 않은 요청이라 실패
    }

    @Test
    void 에러_메시지에는_status와_호출_이름만_있고_본문_토큰은_없다() {
        server.expect(requestTo(API + "/channels/600/webhooks"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"url\":\"https://webhook.test/42/secret-token\"}"));

        assertThatThrownBy(() -> client.createWebhook("600", "aiops"))
                .isInstanceOfSatisfying(DiscordApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(500);
                    assertThat(e.getMessage()).contains("createWebhook").contains("500")
                            .doesNotContain("secret-token").doesNotContain("fake-bot-token");
                    assertThat(e.getCause()).isNull();
                });
    }

    @Test
    void 타임아웃이면_응답_없음_DiscordApiException() {
        server.expect(requestTo(API + "/guilds/111/roles"))
                .andRespond(withException(new SocketTimeoutException("timeout")));

        assertThatThrownBy(() -> client.createRole("team-shop"))
                .isInstanceOfSatisfying(DiscordApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(DiscordApiException.NO_RESPONSE));
    }

    @Test
    void 설정의_RestClient는_봇_토큰을_Authorization_헤더로_붙인다() {
        // DiscordClientConfig.create가 만든 RestClient를 가짜 서버에 묶어서 헤더 확인
        RestClient.Builder builder = DiscordClientConfig.create(PROPS).mutate();
        MockRestServiceServer s = MockRestServiceServer.bindTo(builder).build();
        s.expect(requestTo(API + "/users/@me"))
                .andExpect(r -> assertThat(r.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bot fake-bot-token"))
                .andRespond(withSuccess("{\"id\":\"777\"}", MediaType.APPLICATION_JSON));

        assertThat(new DiscordApiClient(builder.build(), PROPS, d -> { }).botUserId()).isEqualTo("777");
        s.verify();
    }
}
