package dev.aiops.starter.safety;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

// 안전성 6 (AI-49, D-087) : Starter 안에서 Error가 나도 팀 앱은 뜨고, Starter만 한 줄 남기고 꺼진다
class InternalErrorSafetyTest {

    private ConfigurableApplicationContext app;

    @AfterEach
    void tearDown() {
        System.clearProperty("aiops.test.fail-on-init");
        if (app != null) {
            app.close();
        }
    }

    @Test
    void Starter_내부_오류가_나도_앱은_뜨고_Starter만_꺼진다() throws Exception {
        System.setProperty("aiops.test.fail-on-init", "nosuchmethod"); // 초기화 중 NoSuchMethodError (버전 불일치 흉내)
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            app = Safety.startApp(Safety.KEY_ARG, "--aiops.endpoint=" + FakeIngestServer.CLOSED_ENDPOINT);
        } finally {
            System.setErr(original);
        }
        int port = Safety.portOf(app);

        assertEquals(200, Safety.get(port, "/ok"));
        assertEquals(500, Safety.get(port, "/boom/npe"));
        assertEquals(1, Safety.linesStartingWith(captured.toString(StandardCharsets.UTF_8), "[aiops] 비활성화됨:"),
                "비활성 안내는 정확히 한 줄");
        assertFalse(Safety.hasAiopsAppender());
        assertFalse(Safety.senderThreadAlive());
    }
}