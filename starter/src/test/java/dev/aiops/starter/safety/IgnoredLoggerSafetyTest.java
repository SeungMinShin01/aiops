package dev.aiops.starter.safety;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

// 안전성 7 (AI-49, D-087) : 전송 쪽 로거(HttpClient·Starter 자신)의 에러는 다시 수집하지 않는다 (무한 반복 방지)
class IgnoredLoggerSafetyTest {

    private FakeIngestServer server;
    private ConfigurableApplicationContext app;

    @BeforeEach
    void setUp() throws IOException {
        server = FakeIngestServer.start();
    }

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.close();
        }
        server.stop();
    }

    @Test
    void 전송_쪽_로거의_에러는_수집하지_않는다() throws Exception {
        app = Safety.startApp(Safety.KEY_ARG, "--aiops.endpoint=" + server.endpoint());
        int port = Safety.portOf(app);

        assertEquals(200, Safety.get(port, "/log/ignored")); // 무시해야 하는 로거 3곳에서 ERROR + 예외
        assertEquals(500, Safety.get(port, "/boom/npe")); // 진짜 에러 1건

        assertTrue(Safety.pollUntil(() -> server.eventCount() >= 1, Duration.ofSeconds(10)), "진짜 에러는 도착한다");
        assertFalse(Safety.pollUntil(() -> server.eventCount() > 1, Duration.ofSeconds(3)),
                "3초를 더 봐도 1건뿐이다. 받은 개수=" + server.eventCount());
    }
}