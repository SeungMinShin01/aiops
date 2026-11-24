package dev.aiops.starter.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.mock.env.MockEnvironment;

import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;

import static org.junit.jupiter.api.Assertions.*;

class AiopsRuntimeTest {

    @AfterEach
    void tearDown() {
        AiopsRuntime.resetForTest();
    }

    private static MockEnvironment withKey() {
        return new MockEnvironment()
                .withProperty("aiops.api-key", "aiops_l_test")
                .withProperty("aiops.endpoint", "http://127.0.0.1:1"); // 닫힌 포트: 보내도 false
    }

    private static int senderThreads() {
        int count = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().equals("aiops-sender") && t.isAlive()) {
                count++;
            }
        }
        return count;
    }

    @Test
    void 키가_없으면_null이고_스레드를_만들지_않는다() {
        int before = senderThreads();
        assertNull(AiopsRuntime.startIfAbsent(new MockEnvironment()));
        assertNull(AiopsRuntime.current());
        assertEquals(before, senderThreads());
    }

    @Test
    void enabled_false면_null() {
        assertNull(AiopsRuntime.startIfAbsent(withKey().withProperty("aiops.enabled", "false")));
    }

    @Test
    void 키가_있으면_부품이_연결되고_전송스레드가_1개_뜬다() {
        int before = senderThreads();
        AiopsRuntime runtime = AiopsRuntime.startIfAbsent(withKey());

        assertNotNull(runtime);
        assertNotNull(runtime.buffer());
        assertNotNull(runtime.sender());
        assertEquals("local", runtime.settings().env());
        assertSame(runtime, AiopsRuntime.current());
        assertEquals(before + 1, senderThreads());
    }

    @Test
    void 두_번_불러도_같은_것을_돌려주고_스레드는_늘지_않는다() {
        int before = senderThreads();
        AiopsRuntime first = AiopsRuntime.startIfAbsent(withKey());
        AiopsRuntime second = AiopsRuntime.startIfAbsent(withKey()); // DevTools 재시작 흉내

        assertSame(first, second);
        assertEquals(before + 1, senderThreads());
    }

    @Test
    void 진입점은_로깅_초기화_다음_순서다() {
        assertTrue(new AiopsEarlyInitializer().getOrder() > LoggingApplicationListener.DEFAULT_ORDER);
    }

    @Test
    void spring_factories에_진입점이_등록되어_있다() throws Exception {
        boolean found = false;
        Enumeration<URL> files = getClass().getClassLoader().getResources("META-INF/spring.factories");
        while (files.hasMoreElements()) {
            try (InputStream in = files.nextElement().openStream()) {
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                if (text.contains("dev.aiops.starter.runtime.AiopsEarlyInitializer")) {
                    found = true;
                }
            }
        }
        assertTrue(found);
    }
}