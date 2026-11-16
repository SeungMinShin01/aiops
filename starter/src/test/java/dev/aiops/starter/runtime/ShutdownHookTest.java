package dev.aiops.starter.runtime;

import com.sun.net.httpserver.HttpServer;
import dev.aiops.protocol.ErrorEvent;
import dev.aiops.protocol.Phase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

// AI-46 : 앱이 끝날 때 버퍼에 남은 이벤트를 마지막으로 한 번 보낸다. onJvmShutdown()을 직접 부른다
class ShutdownHookTest {

    private HttpServer server;
    private ExecutorService pool;
    private final List<String> eventBodies = new CopyOnWriteArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private volatile boolean hang = false;

    @BeforeEach
    void setUp() throws IOException {
        AiopsRuntime.resetForTest();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        pool = Executors.newCachedThreadPool();
        server.setExecutor(pool);
        server.createContext("/", exchange -> {
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (hang) {
                    release.await(10, TimeUnit.SECONDS); // 응답을 안 주는 서버 흉내
                }
                if (exchange.getRequestURI().getPath().endsWith("/events")) {
                    eventBodies.add(body);
                }
                exchange.sendResponseHeaders(202, -1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        server.stop(0);
        pool.shutdownNow();
        AiopsRuntime.resetForTest();
    }

    private AiopsRuntime startRuntime() {
        AiopsRuntime runtime = AiopsRuntime.startIfAbsent(new MockEnvironment()
                .withProperty("aiops.api-key", "aiops_l_test")
                .withProperty("aiops.endpoint", "http://127.0.0.1:" + server.getAddress().getPort()));
        assertNotNull(runtime);
        return runtime;
    }

    private static ErrorEvent event(int i) {
        return new ErrorEvent(1L, "ERROR", "java.lang.IllegalStateException", "left-" + i,
                List.of(), List.of(), "main", Phase.RUNTIME);
    }

    private static boolean senderAlive() {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().equals("aiops-sender") && t.isAlive()) {
                return true;
            }
        }
        return false;
    }

    @Test
    void 버퍼에_남은_이벤트를_보내고_전송_스레드를_멈춘다() throws Exception {
        AiopsRuntime runtime = startRuntime();
        runtime.buffer().offer(event(0));
        runtime.buffer().offer(event(1));
        runtime.buffer().offer(event(2));

        AiopsRuntime.onJvmShutdown();

        String all = String.join("\n", eventBodies);
        assertTrue(all.contains("left-0") && all.contains("left-1") && all.contains("left-2"),
                "남은 3개가 도착했다");
        assertEquals(0, runtime.buffer().size());
        Thread.sleep(200); // 스레드가 끝나는 것을 잠깐 기다린다
        assertFalse(senderAlive());
    }

    @Test
    void 서버가_응답을_안_줘도_2초_남짓_안에_돌아온다() {
        hang = true;
        AiopsRuntime runtime = startRuntime();
        runtime.buffer().offer(event(0));

        assertTimeout(Duration.ofMillis(2500), () -> AiopsRuntime.onJvmShutdown());
    }

    @Test
    void 종료_훅은_JVM에_한_번만_등록된다() {
        startRuntime();
        assertTrue(AiopsRuntime.shutdownHookRegistered());

        AiopsRuntime.resetForTest();
        startRuntime(); // DevTools 재시작 흉내

        assertFalse(AiopsRuntime.registerShutdownHookOnce(), "이미 등록돼 있으면 다시 등록하지 않는다");
    }

    @Test
    void 시작_전이거나_꺼져_있으면_아무것도_하지_않는다() {
        assertDoesNotThrow(() -> AiopsRuntime.onJvmShutdown());

        assertTrue(eventBodies.isEmpty());
    }
}