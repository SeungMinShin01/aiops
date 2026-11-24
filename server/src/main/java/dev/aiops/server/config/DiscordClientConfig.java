package dev.aiops.server.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 디스코드 REST API(v10)용 RestClient (AI-91).
 * 봇 토큰은 DiscordProperties에서만 읽는다. 헤더가 로그에 찍히지 않게 로깅 인터셉터는 붙이지 않는다.
 */
@Configuration
public class DiscordClientConfig {

    public static final String BASE_URL = "https://discord.com/api/v10";
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    @Bean
    @Qualifier("discordRestClient")
    public RestClient discordRestClient(DiscordProperties properties) {
        return create(properties);
    }

    /** Spring 없이도 같은 설정으로 만들 수 있게 (라이브 테스트용). */
    public static RestClient create(DiscordProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(BASE_URL)
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        if (properties.isBotConfigured()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bot " + properties.botToken());
        }
        return builder.build();
    }
}
