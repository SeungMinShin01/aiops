package dev.aiops.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 마일스톤 2 (AI-60): 수집 → 그룹화 → 설명(LLM) → 알림이 실제 서버 위에서 끝까지 이어지는지.
 * <p>
 * LLM 키를 비워 FakeLlmClient(고정 문장)를 쓴다. 터미널에 키가 있어도 실제 LLM을 부르지 않게 속성으로 고정.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "aiops.llm.api-key=")
class EndToEndFlowTest extends EndToEndFlowSupport {

    @Test
    void 새_에러는_설명이_실린_웹훅_1번_같은_에러는_추가_알림_없음() throws Exception {
        // 1. 첫 에러 → 202 → 처리 풀에서 그룹 생성 → 설명 저장 → 웹훅 1번
        assertThat(postEvents()).isEqualTo(202);
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
                .until(() -> webhookBodies.size() == 1);

        assertThat(groupCount()).isEqualTo(1L);
        assertThat(explanation()).contains("(가짜 응답)");   // 알림보다 설명이 먼저 저장됨

        String body = webhookBodies.get(0);
        assertThat(body).contains("NullPointerException");
        assertThat(body).contains("원인 후보").contains("확인 방법").contains("해결 방향");   // 설명 세 칸
        assertThat(body).contains("(가짜 응답)");
        assertThat(body).doesNotContain("설명 준비 중");
        assertThat(body).doesNotContain("test-token"); // 웹훅 주소가 본문에 섞이지 않음

        // 2. 같은 에러 1건 더 → 그룹 횟수 2, 웹훅은 그대로 1번, 설명도 그대로
        String first = explanation();
        assertThat(postEvents()).isEqualTo(202);
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
                .until(() -> occurrence() == 2);
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2))
                .until(() -> webhookBodies.size() == 1);   // 잠시 지켜봐도 추가 알림 없음
        assertThat(explanation()).isEqualTo(first);
    }
}
