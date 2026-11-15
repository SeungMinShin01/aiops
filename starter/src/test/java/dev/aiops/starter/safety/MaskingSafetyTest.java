package dev.aiops.starter.safety;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

// 안전성 5 (AI-49) : 에러 메시지의 민감정보는 서버에 *** 로 도착한다
class MaskingSafetyTest {

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
    void 민감정보는_가려져서_도착한다() throws Exception {
        app = Safety.startApp(Safety.KEY_ARG, "--aiops.endpoint=" + server.endpoint());
        int port = Safety.portOf(app);

        assertEquals(500, Safety.get(port, "/boom/sensitive"));

        assertTrue(Safety.pollUntil(() -> server.eventCount() >= 1, Duration.ofSeconds(10)), "이벤트가 도착했다");
        String received = server.allEvents();
        assertFalse(received.contains("password=1234"));
        assertFalse(received.contains("test@example.com"));
        assertFalse(received.contains("abc.def.ghi"));
        assertTrue(received.contains("***"));
    }
}