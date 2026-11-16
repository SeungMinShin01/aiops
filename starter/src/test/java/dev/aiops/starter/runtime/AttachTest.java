package dev.aiops.starter.runtime;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import dev.aiops.starter.capture.AiopsLogbackAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.context.ApplicationEvent;
import org.springframework.mock.env.MockEnvironment;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.*;

class AttachTest {

    private LoggerContext context;
    private Logger root;

    @BeforeEach
    void setUp() {
        context = (LoggerContext) LoggerFactory.getILoggerFactory();
        root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        AiopsRuntime.resetForTest();
        assertNotNull(AiopsRuntime.startIfAbsent(new MockEnvironment()
                .withProperty("aiops.api-key", "aiops_l_test")
                .withProperty("aiops.endpoint", "http://127.0.0.1:1")));
    }

    @AfterEach
    void tearDown() {
        AiopsRuntime.resetForTest();
    }

    private int aiopsAppenders() {
        int count = 0;
        Iterator<Appender<ILoggingEvent>> it = root.iteratorForAppenders();
        while (it.hasNext()) {
            if (AiopsLogbackAppender.NAME.equals(it.next().getName())) {
                count++;
            }
        }
        return count;
    }

    // ---- 점검 1: 순서 ----

    @Test
    void 순서는_로깅_리스너_바로_다음() {
        assertEquals(LoggingApplicationListener.DEFAULT_ORDER + 1, new AiopsEarlyInitializer().getOrder());
    }

    // ---- 점검 2: 재장착 ----

    @Test
    void 리셋_후_장착하면_AIOPS_1개이고_시작돼_있다() {
        context.reset(); // Spring 로깅 초기화가 하는 일
        AiopsRuntime.attachAppenderIfMissing();

        assertEquals(1, aiopsAppenders());
        assertTrue(root.getAppender(AiopsLogbackAppender.NAME).isStarted());
    }

    @Test
    void 두_번_불러도_1개() {
        context.reset();
        AiopsRuntime.attachAppenderIfMissing();
        AiopsRuntime.attachAppenderIfMissing();

        assertEquals(1, aiopsAppenders());
    }

    @Test
    void 리셋으로_떨어지면_다시_붙는다() {
        context.reset();
        AiopsRuntime.attachAppenderIfMissing();
        context.reset(); // DevTools 재시작 흉내
        assertEquals(0, aiopsAppenders());

        AiopsRuntime.attachAppenderIfMissing();
        assertEquals(1, aiopsAppenders());
    }

    // ---- 점검 3: 실제로 잡히는지 (AI-42 확인 항목 포함) ----

    @Test
    void 세_가지_로그_중_ERROR와_예외만_버퍼에_1개() {
        context.reset();
        AiopsRuntime.attachAppenderIfMissing();
        org.slf4j.Logger team = LoggerFactory.getLogger("com.example.OrderService");

        team.info("정보", new IllegalStateException("x"));
        team.error("예외 없는 에러");
        team.error("주문 실패", new IllegalStateException("login failed: password=1234"));

        AiopsRuntime runtime = AiopsRuntime.current();
        assertEquals(1, runtime.buffer().size());
        assertEquals("login failed: password=***", runtime.buffer().drain(10).get(0).message());
    }

    // ---- 점검 4: 비활성 처리 (D-087) ----

    @Test
    void 리스너_안에서_NoSuchMethodError가_나도_정상_반환하고_한_줄만_찍고_꺼진다() {
        context.reset();
        AiopsRuntime.attachAppenderIfMissing();
        AiopsEarlyInitializer broken = new AiopsEarlyInitializer() {
            @Override
            void handle(ApplicationEvent event) {
                throw new NoSuchMethodError("boot version mismatch"); // Boot 버전 불일치 흉내
            }
        };
        ApplicationEvent event = new ApplicationEvent("test") {
        };

        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            assertDoesNotThrow(() -> broken.onApplicationEvent(event));
            assertDoesNotThrow(() -> broken.onApplicationEvent(event)); // 두 번째는 바로 리턴
        } finally {
            System.setErr(original);
        }

        long lines = captured.toString(StandardCharsets.UTF_8).lines()
                .filter(line -> line.startsWith("[aiops] 비활성화됨:"))
                .count();
        assertEquals(1, lines);
        assertTrue(AiopsRuntime.isDisabled());
        assertNull(AiopsRuntime.current());
        assertEquals(0, aiopsAppenders()); // 어펜더도 뗐다
    }
}