package dev.aiops.starter.safety;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import static org.junit.jupiter.api.Assertions.*;

// 안전성 1 (AI-49) : 수집 서버가 꺼져 있어도 팀 앱은 뜨고, 느려지지 않는다
class ServerDownSafetyTest {

    private ConfigurableApplicationContext app;

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.close();
        }
    }

    @Test
    void 수집_서버가_꺼져_있어도_앱은_뜨고_느려지지_않는다() throws Exception {
        app = Safety.startApp(Safety.KEY_ARG, "--aiops.endpoint=" + FakeIngestServer.CLOSED_ENDPOINT);
        int port = Safety.portOf(app);
        for (int i = 0; i < 200; i++) {
            Safety.get(port, "/ok"); // 예열
        }

        double quiet = averageOkMillis(port, false); // 보낼 것이 없을 때
        double busy = averageOkMillis(port, true); // 에러가 계속 나고, 전송은 계속 실패하는 중

        assertEquals(500, Safety.get(port, "/boom/npe"));
        assertEquals(200, Safety.get(port, "/ok"), "에러 뒤에도 앱은 살아 있다");
        assertTrue(busy <= Math.max(quiet * 2, quiet + 5),
                "전송 실패 중에도 응답이 2배(또는 +5ms)를 넘지 않는다. quiet=" + quiet + "ms busy=" + busy + "ms");
    }

    /** 3초 동안 /ok를 계속 부른 평균(ms). withErrors면 20번마다 에러를 한 번 낸다 (그 호출은 재지 않는다) */
    private static double averageOkMillis(int port, boolean withErrors) throws Exception {
        long totalNanos = 0;
        int calls = 0;
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (withErrors && calls % 20 == 0) {
                Safety.get(port, "/boom/npe");
            }
            long started = System.nanoTime();
            Safety.get(port, "/ok");
            totalNanos += System.nanoTime() - started;
            calls++;
        }
        return totalNanos / 1_000_000.0 / calls;
    }
}