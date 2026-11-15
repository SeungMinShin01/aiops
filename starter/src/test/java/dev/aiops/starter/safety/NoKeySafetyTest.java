package dev.aiops.starter.safety;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

// 안전성 2 (AI-49) : 키가 없으면 경고 한 줄만 남기고 Starter는 아무것도 하지 않는다
class NoKeySafetyTest {

    private ConfigurableApplicationContext app;

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.close();
        }
    }

    @Test
    void 키가_없으면_경고_한_줄만_남기고_아무것도_붙이지_않는다() throws Exception {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            app = Safety.startApp("--aiops.api-key="); // 빈 값으로 덮어쓴다 (PC 환경변수의 영향을 받지 않게)
        } finally {
            System.setErr(original);
        }
        int port = Safety.portOf(app);

        assertEquals(200, Safety.get(port, "/ok"));
        assertEquals(500, Safety.get(port, "/boom/npe"));
        assertEquals(1, Safety.linesStartingWith(captured.toString(StandardCharsets.UTF_8),
                "[aiops] aiops.api-key 없음"));
        assertFalse(Safety.hasAiopsAppender(), "어펜더를 붙이지 않는다");
        assertFalse(Safety.senderThreadAlive(), "전송 스레드를 만들지 않는다");
    }
}