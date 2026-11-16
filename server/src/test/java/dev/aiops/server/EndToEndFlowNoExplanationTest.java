package dev.aiops.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 마일스톤 2 (AI-60): 설명을 못 만들어도 알림은 간다.
 * <p>
 * 하루 상한을 0으로 두면 ExplanationService가 LLM을 부르지 않고 QUOTA_EXCEEDED를 돌려준다
 * (LLM 장애·응답 없음과 같은 "설명 없음" 경로).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"aiops.llm.api-key=", "aiops.llm.daily-limit=0"})
class EndToEndFlowNoExplanationTest extends EndToEndFlowSupport {

    @Test
    void 설명이_없어도_알림은_설명_준비_중으로_간다() throws Exception {
        assertThat(postEvents()).isEqualTo(202);
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
                .until(() -> webhookBodies.size() == 1);

        assertThat(groupCount()).isEqualTo(1L);
        assertThat(explanation()).isNull();   // NULL로 남아야 재시도 스케줄러가 다시 시도한다

        String body = webhookBodies.get(0);
        assertThat(body).contains("NullPointerException");
        assertThat(body).contains("설명 준비 중");
        assertThat(body).doesNotContain("test-token");
    }
}
