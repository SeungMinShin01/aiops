package dev.aiops.server.client;

import dev.aiops.server.config.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * OpenAI 호환 chat API 구현 (AI-54). POST {base-url}/chat/completions.
 * <ul>
 * <li>실패는 전부 Optional.empty() + 경고 로그 한 줄. 예외를 밖으로 던지지 않는다</li>
 * <li>로그에는 상태 코드·예외 종류만. 요청·응답 본문과 헤더는 남기지 않는다 (프롬프트에 에러 메시지가 들어 있다)</li>
 * <li>재시도 없음, 스트리밍 없음</li>
 * </ul>
 */
public class OpenAiCompatibleLlmClient implements LlmClient {

    static final double TEMPERATURE = 0.2;
    static final int MAX_TOKENS = 800;

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);

    private final RestClient rest;
    private final String model;

    public OpenAiCompatibleLlmClient(RestClient rest, LlmProperties properties) {
        this.rest = rest;
        this.model = properties.model();
    }

    @Override
    public Optional<String> complete(LlmPrompt prompt) {
        if (prompt == null) {
            return Optional.empty();
        }
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", nullToEmpty(prompt.system())),
                        Map.of("role", "user", "content", nullToEmpty(prompt.user()))),
                "temperature", TEMPERATURE,
                "max_tokens", MAX_TOKENS);
        try {
            Map<?, ?> response = rest.post()
                    .uri("/chat/completions")
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            return contentOf(response);
        } catch (RestClientResponseException e) {
            log.warn("LLM 호출 실패 status={}", e.getStatusCode().value());
        } catch (ResourceAccessException e) {
            log.warn("LLM 호출 실패 (연결·타임아웃)");
        } catch (RuntimeException e) {
            log.warn("LLM 응답 처리 실패 {}", e.getClass().getSimpleName());
        }
        return Optional.empty();
    }

    /** choices[0].message.content만 꺼낸다. 모양이 다르거나 비어 있으면 empty */
    private static Optional<String> contentOf(Map<?, ?> response) {
        if (response == null) {
            return Optional.empty();
        }
        if (!(response.get("choices") instanceof List<?> choices) || choices.isEmpty()) {
            return Optional.empty();
        }
        if (!(choices.get(0) instanceof Map<?, ?> first)) {
            return Optional.empty();
        }
        if (!(first.get("message") instanceof Map<?, ?> message)) {
            return Optional.empty();
        }
        if (!(message.get("content") instanceof String content) || content.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(content.strip());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}