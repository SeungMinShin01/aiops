package dev.aiops.starter.runtime;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

// AI-45 : 앱이 뜨다가 실패하면 그 예외를 STARTUP 이벤트로 바로 보낸다
class StartupFailureTest {

    private HttpServer server;
    private final List<String> eventBodies = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        AiopsRuntime.resetForTest();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (exchange.getRequestURI().getPath().endsWith("/events")) {
                    eventBodies.add(body);
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

    private String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Bean을 만들다가 실패하는 앱 (DB 연결 실패 흉내). 값은 가짜다 */
    @Configuration
    static class BrokenApp {
        @Bean
        Object dataSource() {
            throw new IllegalStateException("db connect failed: password=hunter2secret");
        }
    }

    private static SpringApplication brokenApp() {
        SpringApplication app = new SpringApplication(BrokenApp.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setBannerMode(Banner.Mode.OFF);
        app.setRegisterShutdownHook(false);
        return app;
    }

    private static boolean hasCauseMessage(Throwable thrown, String text) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(text)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void startupFailureIsReported() {
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> brokenApp().run("--aiops.api-key=aiops_l_test", "--aiops.endpoint=" + endpoint()));

        assertTrue(hasCauseMessage(thrown, "db connect failed"), "원래 시작 실패 예외가 그대로 나온다");
        assertTrue(eventBodies.stream().anyMatch(body -> body.contains("\"STARTUP\"")),
                "STARTUP 이벤트가 run()이 끝나기 전에 도착해 있다");
        assertFalse(String.join("\n", eventBodies).contains("hunter2secret"), "마스킹 없이 보내지 않는다");
    }

    @Test
    void 키가_없으면_아무것도_보내지_않는다() {
        assertThrows(RuntimeException.class, () -> brokenApp().run("--aiops.endpoint=" + endpoint()));

        assertTrue(eventBodies.isEmpty());
        assertNull(AiopsRuntime.current());
    }

    @Test
    void 서버가_꺼져_있어도_3초_남짓_안에_돌아온다() {
        assertNotNull(AiopsRuntime.startIfAbsent(new MockEnvironment()
                .withProperty("aiops.api-key", "aiops_l_test")
                .withProperty("aiops.endpoint", "http://127.0.0.1:1"))); // 닫힌 포트

        assertTimeout(Duration.ofMillis(3500),
                () -> AiopsRuntime.reportStartupFailure(new IllegalStateException("x")));
    }

    @Test
    void 변환_중에_Error가_나도_밖으로_나오지_않고_Starter도_꺼지지_않는다() {
        assertNotNull(AiopsRuntime.startIfAbsent(new MockEnvironment()
                .withProperty("aiops.api-key", "aiops_l_test")
                .withProperty("aiops.endpoint", endpoint())));
        Throwable broken = new RuntimeException("x") {
            @Override
            public String getMessage() {
                throw new StackOverflowError("테스트용");
            }
        };

        assertDoesNotThrow(() -> AiopsRuntime.reportStartupFailure(broken));

        assertFalse(AiopsRuntime.isDisabled());
        assertTrue(eventBodies.isEmpty());
    }

    @Test
    void 시작_전이면_아무것도_하지_않는다() {
        assertDoesNotThrow(() -> AiopsRuntime.reportStartupFailure(new IllegalStateException("x")));

        assertTrue(eventBodies.isEmpty());
    }
}