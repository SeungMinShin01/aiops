package dev.aiops.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * LLM 설정 (aiops.llm.*). 키는 환경변수 AIOPS_LLM_API_KEY에서만 온다 (규칙 10).
 * toString에는 키가 찍히지 않는다.
 *
 * @param apiKey  API 키 — 비밀값
 * @param baseUrl OpenAI 호환 API 주소 (끝의 /는 뗀다)
 * @param model   모델 이름
 * @param timeout 연결·읽기 타임아웃 (기본 20초)
 */
@ConfigurationProperties("aiops.llm")
public record LlmProperties(String apiKey, String baseUrl, String model, Duration timeout) {

    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);

    public LlmProperties {
        if (timeout == null) {
            timeout = DEFAULT_TIMEOUT;
        }
        if (baseUrl != null && baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
    }

    /** 키·주소·모델이 모두 있어야 실제 LLM을 부른다. 하나라도 없으면 가짜 구현을 쓴다. */
    public boolean isConfigured() {
        return hasText(apiKey) && hasText(baseUrl) && hasText(model);
    }

    @Override
    public String toString() {
        return "LlmProperties[apiKey=" + (hasText(apiKey) ? "***" : "(없음)")
                + ", baseUrl=" + baseUrl
                + ", model=" + model
                + ", timeout=" + timeout + "]";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}