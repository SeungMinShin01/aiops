package dev.aiops.server.client;

import dev.aiops.server.config.LlmConfig;
import dev.aiops.server.config.LlmProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenAiCompatibleLlmClientTest {

    static final String BASE = "https://llm.test/v1";
    // 가짜 값 (진짜 키 아님, 규칙 10)
    static final LlmProperties PROPS = new LlmProperties("fake-llm-key", BASE, "test-model", Duration.ofSeconds(1));
    static final LlmPrompt PROMPT = new LlmPrompt("너는 도우미다", "NullPointerException at OrderService");

    MockRestServiceServer server;
    OpenAiCompatibleLlmClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = LlmConfig.builder(PROPS);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new OpenAiCompatibleLlmClient(builder.build(), PROPS);
    }

    private static String ok(String content) {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + content + "\"}}]}";
    }

    @Test
    void 정상_응답이면_content를_돌려주고_요청은_명세_모양이다() {
        server.expect(requestTo(BASE + "/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer fake-llm-key"))
                .andExpect(jsonPath("$.model").value("test-model"))
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[0].content").value("너는 도우미다"))
                .andExpect(jsonPath("$.messages[1].role").value("user"))
                .andExpect(jsonPath("$.messages[1].content").value("NullPointerException at OrderService"))
                .andExpect(jsonPath("$.temperature").value(0.2))
                .andExpect(jsonPath("$.max_tokens").value(800))
                .andRespond(withSuccess(ok("원인 후보: 주문이 null"), MediaType.APPLICATION_JSON));

        Optional<String> result = client.complete(PROMPT);

        assertThat(result).contains("원인 후보: 주문이 null");
        server.verify();
    }

    @Test
    void 서버_오류_500이면_empty() {
        server.expect(requestTo(BASE + "/chat/completions"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThat(client.complete(PROMPT)).isEmpty();
    }

    @Test
    void 인증_실패_401이면_empty() {
        server.expect(requestTo(BASE + "/chat/completions"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThat(client.complete(PROMPT)).isEmpty();
    }

    @Test
    void 타임아웃이면_예외_없이_empty() {
        server.expect(requestTo(BASE + "/chat/completions"))
                .andRespond(withException(new SocketTimeoutException("read timed out")));

        assertThat(client.complete(PROMPT)).isEmpty();
    }

    @Test
    void choices가_비어_있으면_empty() {
        server.expect(requestTo(BASE + "/chat/completions"))
                .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));

        assertThat(client.complete(PROMPT)).isEmpty();
    }

    @Test
    void content가_공백이면_empty() {
        server.expect(requestTo(BASE + "/chat/completions"))
                .andRespond(withSuccess(ok("   "), MediaType.APPLICATION_JSON));

        assertThat(client.complete(PROMPT)).isEmpty();
    }

    @Test
    void 응답이_JSON이_아니면_empty() {
        server.expect(requestTo(BASE + "/chat/completions"))
                .andRespond(withSuccess("not json", MediaType.APPLICATION_JSON));

        assertThat(client.complete(PROMPT)).isEmpty();
    }

    @Test
    void 프롬프트가_null이면_호출하지_않고_empty() {
        assertThat(client.complete(null)).isEmpty();
        server.verify(); // 기대한 요청 0개, 실제 요청 0개
    }
}