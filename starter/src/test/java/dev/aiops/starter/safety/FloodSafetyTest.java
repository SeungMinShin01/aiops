package dev.aiops.starter.safety;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

// 안전성 4 (AI-49) : 서버가 느린데 에러가 1,500개 쏟아져도 앱은 멀쩡하고, 버퍼는 1,000에서 막히고, 메모리가 새지 않는다
class FloodSafetyTest {

    private static final Pattern DROPPED = Pattern.compile("\"droppedCount\"\\s*:\\s*(\\d+)");

    private FakeIngestServer server;
    private ConfigurableApplicationContext app;

    @BeforeEach
    void setUp() throws IOException {
        server = FakeIngestServer.start();
        server.delayResponses(10_000); // 응답이 10초 걸리는 서버 (Starter의 요청 제한 3초보다 길다)
    }

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.close();
        }
        server.stop();
    }

    @Test
    void 에러_1500개가_쏟아져도_앱은_정상이고_넘친_것은_버린다() throws Exception {
        app = Safety.startApp(Safety.KEY_ARG, "--aiops.endpoint=" + server.endpoint());
        int port = Safety.portOf(app);
        for (int i = 0; i < 50; i++) {
            Safety.get(port, "/ok"); // 예열
        }
        long heapBefore = usedHeapAfterGc();

        for (int i = 0; i < 1500; i++) {
            assertEquals(500, Safety.get(port, "/boom/npe"), "에러 응답도 평소처럼 돌아온다");
        }

        assertEquals(200, Safety.get(port, "/ok"), "쏟아진 뒤에도 앱은 살아 있다");
        assertTrue(Safety.pollUntil(() -> maxDroppedCount() >= 500, Duration.ofSeconds(30)),
                "1,500개 중 500개 이상을 버렸다고 서버에 알린다 (= 버퍼가 1,000에서 막혔다). 받은 최대 droppedCount="
                        + maxDroppedCount());
        long grown = usedHeapAfterGc() - heapBefore;
        assertTrue(grown <= 50L * 1024 * 1024, "힙 증가 50MB 이내: " + (grown / 1024 / 1024) + "MB");
    }

    /** 서버가 받은 묶음들의 droppedCount 중 가장 큰 값 */
    private int maxDroppedCount() {
        int max = 0;
        for (String body : server.eventBodies()) {
            Matcher matcher = DROPPED.matcher(body);
            while (matcher.find()) {
                max = Math.max(max, Integer.parseInt(matcher.group(1)));
            }
        }
        return max;
    }

    private static long usedHeapAfterGc() {
        System.gc();
        System.gc();
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
}