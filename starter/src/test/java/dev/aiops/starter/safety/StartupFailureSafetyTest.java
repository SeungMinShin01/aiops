package dev.aiops.starter.safety;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

// 안전성 3 (AI-49) : 앱이 뜨다가 실패하면 그 예외가 STARTUP 이벤트로 도착하고, 원래 실패는 그대로 나온다
class StartupFailureSafetyTest {

    private FakeIngestServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = FakeIngestServer.start();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void 시작_실패가_STARTUP_이벤트로_도착한다() {
        assertThrows(RuntimeException.class, () -> Safety.startApp(
                Safety.KEY_ARG, "--aiops.endpoint=" + server.endpoint(), "--safety.fail=true"));

        String received = server.allEvents();
        assertTrue(received.contains("\"STARTUP\""), "run()이 끝나기 전에 이미 도착해 있다");
        assertFalse(received.contains("hunter2secret"), "마스킹 없이 보내지 않는다");
    }
}