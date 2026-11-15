package dev.aiops.server.config;

import dev.aiops.server.client.FakeLlmClient;
import dev.aiops.server.client.LlmClient;
import dev.aiops.server.client.OpenAiCompatibleLlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * LlmClient 구현을 고른다 (AI-54). 키·주소·모델이 모두 있으면 실구현, 아니면 가짜.
 * <p>
 * 조건부 Bean 대신 메서드 하나에서 고르는 이유: yml이 ${AIOPS_LLM_API_KEY:} 라서 키가 없어도
 * 속성은 "빈 문자열"로 존재하고, @ConditionalOnProperty는 빈 문자열을 통과시킨다.
 * RestClient는 Bean으로 내놓지 않는다 (인증 헤더가 들어 있어 다른 코드가 주입받지 못하게).
 * 헤더가 로그에 찍히지 않게 로깅 인터셉터는 붙이지 않는다.
 */
@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    @Bean
    public LlmClient llmClient(LlmProperties properties) {
        if (!properties.isConfigured()) {
            log.info("LLM 설정 없음 → FakeLlmClient 사용 (고정 문장)");
            return new FakeLlmClient();
        }
        log.info("LLM 연결 model={} baseUrl={}", properties.model(), properties.baseUrl());
        return new OpenAiCompatibleLlmClient(builder(properties).build(), properties);
    }

    /** 테스트가 같은 설정(주소·헤더)으로 MockRestServiceServer를 붙일 수 있게 Builder로 내준다. */
    public static RestClient.Builder builder(LlmProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.timeout());
        factory.setReadTimeout(properties.timeout());

        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey());
    }
}