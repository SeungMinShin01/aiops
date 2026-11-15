package dev.aiops.starter.runtime;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

// AI-47 : 준비 완료 뒤 hello가 서버에 1번 도착하는지
class ReadyHelloTest {

    private HttpServer server;
    private final List<String> helloBodies = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        AiopsRuntime.resetForTest();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (exchange.getRequestURI().getPath().endsWith("/hello")) {
                    helloBodies.add(body);
                }
                exchange.sendResponseHeaders(202, -1);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        AiopsRuntime.resetForTest();
    }

    private MockEnvironment environment() {
        // 가짜 값 (진짜 비밀값 아님)
        return new MockEnvironment()
                .withProperty("aiops.api-key", "aiops_l_test")
                .withProperty("aiops.endpoint", "http://127.0.0.1:" + server.getAddress().getPort())
                .withProperty("spring.datasource.password", "secret123");
    }

    private boolean waitForHello() throws InterruptedException {
        for (int i = 0; i < 150 && helloBodies.isEmpty(); i++) {
            Thread.sleep(20);
        }
        return !helloBodies.isEmpty();
    }

    @Test
    void 준비_완료_뒤_hello가_한_번_도착하고_값은_없다() throws InterruptedException {
        MockEnvironment environment = environment();
        assertNotNull(AiopsRuntime.startIfAbsent(environment));

        AiopsRuntime.submitHelloOnce(environment);
        AiopsRuntime.submitHelloOnce(environment); // 재시작 흉내: 두 번째는 무시

        assertTrue(waitForHello(), "hello가 도착했다");
        Thread.sleep(500);
        assertEquals(1, helloBodies.size());
        String body = helloBodies.get(0);
        assertTrue(body.contains("spring.datasource.password"));
        assertFalse(body.contains("secret123"));
        assertFalse(body.contains("aiops_l_test"), "우리 키 값도 본문에 없다");
    }

    @Test
    void 시작_전이면_아무것도_보내지_않는다() throws InterruptedException {
        AiopsRuntime.submitHelloOnce(new MockEnvironment());

        Thread.sleep(400);
        assertTrue(helloBodies.isEmpty());
    }
}