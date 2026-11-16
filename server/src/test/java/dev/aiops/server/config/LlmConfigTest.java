package dev.aiops.server.config;

import dev.aiops.server.client.FakeLlmClient;
import dev.aiops.server.client.OpenAiCompatibleLlmClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class LlmConfigTest {

    static final String URL = "https://llm.test/v1";

    @Test
    void 키가_비어_있으면_가짜_구현() {
        assertThat(new LlmConfig().llmClient(new LlmProperties("", URL, "m", null)))
                .isInstanceOf(FakeLlmClient.class);
        assertThat(new LlmConfig().llmClient(new LlmProperties(null, URL, "m", null)))
                .isInstanceOf(FakeLlmClient.class);
    }

    @Test
    void 키_주소_모델이_모두_있으면_실구현() {
        assertThat(new LlmConfig().llmClient(new LlmProperties("fake-llm-key", URL, "m", null)))
                .isInstanceOf(OpenAiCompatibleLlmClient.class);
    }

    @Test
    void toString에_키가_찍히지_않는다() {
        String printed = new LlmProperties("fake-llm-key", URL, "m", null).toString();

        assertThat(printed).doesNotContain("fake-llm-key").contains("apiKey=***");
    }

    @Test
    void 타임아웃_기본값은_20초이고_주소_끝의_슬래시는_뗀다() {
        LlmProperties props = new LlmProperties("k", URL + "/", "m", null);

        assertThat(props.timeout()).isEqualTo(Duration.ofSeconds(20));
        assertThat(props.baseUrl()).isEqualTo(URL);
    }
}