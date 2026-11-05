package dev.aiops.server.client;

import dev.aiops.server.config.DiscordClientConfig;
import dev.aiops.server.config.DiscordProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 팀원 디스코드 연결용 OAuth2 호출 (AI-93). 봇 토큰이 아니라 <b>사용자</b> 토큰으로 부르므로 DiscordApiClient와 분리.
 * <p>
 * 사용자 access_token은 반환값으로만 넘기고 필드·로그·예외에 남기지 않는다 (D-070, 규칙 10).
 * 예외는 DiscordApiException(status, 호출 이름)만 — 응답 본문에 토큰이 섞일 수 있어 담지 않는다.
 */
@Component
public class DiscordOAuthClient {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient rest;
    private final DiscordProperties properties;

    @Autowired
    public DiscordOAuthClient(DiscordProperties properties) {
        this(defaultClient(), properties);
    }

    /** 테스트에서 MockRestServiceServer에 묶은 RestClient를 넣기 위한 생성자 */
    DiscordOAuthClient(RestClient rest, DiscordProperties properties) {
        this.rest = rest;
        this.properties = properties;
    }

    private static RestClient defaultClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        // 봇 Authorization 헤더 없음 (사용자 토큰은 호출마다 따로 붙인다)
        return RestClient.builder().baseUrl(DiscordClientConfig.BASE_URL).requestFactory(factory).build();
    }

    /** POST /oauth2/token — 인가 code → 사용자 access_token */
    public String exchange(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", properties.redirectUri());

        Map<?, ?> res = call("oauthExchange", () -> rest.post()
                .uri("/oauth2/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class));
        Object token = (res == null) ? null : res.get("access_token");
        if (token == null) {
            throw new DiscordApiException(DiscordApiException.NO_RESPONSE, "oauthExchange");
        }
        return token.toString();
    }

    /** GET /users/@me (Bearer 사용자 토큰) → 디스코드 사용자 id */
    public String me(String accessToken) {
        Map<?, ?> res = call("oauthMe", () -> rest.get()
                .uri("/users/@me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .body(Map.class));
        Object id = (res == null) ? null : res.get("id");
        if (id == null) {
            throw new DiscordApiException(DiscordApiException.NO_RESPONSE, "oauthMe");
        }
        return id.toString();
    }

    private static <T> T call(String operation, Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            throw new DiscordApiException(e.getStatusCode().value(), operation);
        } catch (ResourceAccessException e) {
            throw new DiscordApiException(DiscordApiException.NO_RESPONSE, operation);
        }
    }
}
