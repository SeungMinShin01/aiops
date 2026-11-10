package dev.aiops.starter.capture;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import dev.aiops.protocol.ErrorEvent;
import dev.aiops.protocol.Phase;
import dev.aiops.protocol.ProtocolConstants;
import dev.aiops.protocol.StackFrame;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DefaultEventMapperTest {

    private static final LoggerContext CONTEXT = new LoggerContext();
    private static final Logger LOGGER = CONTEXT.getLogger("com.example.OrderService");

    private final EventMapper mapper = new DefaultEventMapper();

    /** 진짜 Logback 이벤트 (log.error(msg, t)를 부른 것과 같다) */
    private static ILoggingEvent errorLog(String message, Throwable t) {
        return new LoggingEvent(Logger.class.getName(), LOGGER, Level.ERROR, message, t, null);
    }

    /** 스택이 n줄인 예외 */
    private static RuntimeException withStack(int n) {
        RuntimeException e = new RuntimeException("deep");
        StackTraceElement[] stack = new StackTraceElement[n];
        for (int i = 0; i < n; i++) {
            stack[i] = new StackTraceElement("com.example.C" + i, "m" + i, "C" + i + ".java", i + 1);
        }
        e.setStackTrace(stack);
        return e;
    }

    private static RuntimeException threeLevels() {
        return new RuntimeException("outer",
                new IllegalStateException("middle",
                        new IOException("inner")));
    }

    // ---- map (어펜더 경로) ----

    @Test
    void 원인_3단_예외는_바깥이_타입이고_원인은_2개다() {
        ErrorEvent e = mapper.map(errorLog("주문 실패", threeLevels()), Phase.RUNTIME);

        assertEquals("java.lang.RuntimeException", e.exceptionType());
        assertEquals("outer", e.message());
        assertEquals(2, e.causes().size());
        assertEquals("java.lang.IllegalStateException", e.causes().get(0).type());
        assertEquals("middle", e.causes().get(0).message());
        assertEquals("java.io.IOException", e.causes().get(1).type());
        assertEquals("inner", e.causes().get(1).message());
        assertEquals("ERROR", e.level());
        assertEquals(Phase.RUNTIME, e.phase());
        assertEquals(Thread.currentThread().getName(), e.thread());
        assertTrue(e.time() > 0);
    }

    @Test
    void 스택이_100줄이어도_30줄만_담는다() {
        ErrorEvent e = mapper.map(errorLog("x", withStack(100)), Phase.RUNTIME);

        assertEquals(ProtocolConstants.MAX_STACK_FRAMES, e.stack().size());
        StackFrame first = e.stack().get(0);
        assertEquals("com.example.C0", first.className());
        assertEquals("m0", first.methodName());
        assertEquals("C0.java", first.fileName());
        assertEquals(1, first.lineNumber());
    }

    @Test
    void 예외가_없는_ERROR_로그는_null() {
        assertNull(mapper.map(errorLog("그냥 에러 메시지", null), Phase.RUNTIME));
    }

    @Test
    void 예외_메시지가_없으면_로그_메시지를_쓴다() {
        ErrorEvent e = mapper.map(errorLog("주문 처리 실패 id=7", new NullPointerException()), Phase.RUNTIME);

        assertEquals("java.lang.NullPointerException", e.exceptionType());
        assertEquals("주문 처리 실패 id=7", e.message());
    }

    @Test
    void 자기_자신을_원인으로_가진_예외도_멈추지_않는다() {
        RuntimeException self = new RuntimeException("loop") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        ErrorEvent e = assertTimeoutPreemptively(Duration.ofSeconds(1),
                () -> mapper.map(errorLog("x", self), Phase.RUNTIME));

        assertNotNull(e);
        assertTrue(e.causes().size() <= DefaultEventMapper.MAX_CAUSES);
    }

    @Test
    void 원인이_20단이어도_10단까지만_담는다() {
        Throwable t = new IllegalStateException("c20");
        for (int i = 19; i >= 1; i--) {
            t = new RuntimeException("c" + i, t);
        }
        ErrorEvent e = mapper.map(errorLog("x", t), Phase.RUNTIME);

        assertEquals(DefaultEventMapper.MAX_CAUSES, e.causes().size());
        assertEquals("c2", e.causes().get(0).message());
    }

    @Test
    void 줄_번호를_모르면_마이너스_1() {
        RuntimeException e = new RuntimeException("native");
        e.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("java.lang.Thread", "sleep0", null, -2) // 네이티브 메서드
        });
        ErrorEvent mapped = mapper.map(errorLog("x", e), Phase.RUNTIME);

        assertEquals(-1, mapped.stack().get(0).lineNumber());
        assertNull(mapped.stack().get(0).fileName());
    }

    @Test
    void map_안에서_무엇이_터져도_null() {
        // 어떤 메서드를 불러도 예외를 던지는 가짜 Logback 이벤트
        ILoggingEvent broken = (ILoggingEvent) Proxy.newProxyInstance(
                ILoggingEvent.class.getClassLoader(),
                new Class<?>[] { ILoggingEvent.class },
                (proxy, method, args) -> {
                    throw new IllegalStateException("boom");
                });
        assertNull(mapper.map(broken, Phase.RUNTIME));
        assertNull(mapper.map(null, Phase.RUNTIME));
    }

    // ---- mapThrowable (시작 실패 경로) ----

    @Test
    void mapThrowable도_같은_규칙이고_단계는_STARTUP() {
        long before = System.currentTimeMillis();
        ErrorEvent e = mapper.mapThrowable(threeLevels(), Phase.STARTUP);

        assertEquals("java.lang.RuntimeException", e.exceptionType());
        assertEquals("outer", e.message());
        assertEquals(2, e.causes().size());
        assertEquals("ERROR", e.level());
        assertEquals(Phase.STARTUP, e.phase());
        assertTrue(e.time() >= before);
        assertFalse(e.stack().isEmpty());
    }

    @Test
    void mapThrowable_스택도_30줄() {
        ErrorEvent e = mapper.mapThrowable(withStack(100), Phase.STARTUP);
        assertEquals(ProtocolConstants.MAX_STACK_FRAMES, e.stack().size());
    }

    @Test
    void mapThrowable_안에서_터지면_null() {
        RuntimeException evil = new RuntimeException("x") {
            @Override
            public StackTraceElement[] getStackTrace() {
                throw new IllegalStateException("boom");
            }
        };
        assertNull(mapper.mapThrowable(evil, Phase.STARTUP));
        assertNull(mapper.mapThrowable(null, Phase.STARTUP));
    }

    // ---- 속도 (팀 앱 스레드) ----

    @Test
    void 스택_30줄_변환이_평균_1ms_안쪽() {
        ILoggingEvent event = errorLog("x", withStack(30));
        for (int i = 0; i < 100; i++) {
            mapper.map(event, Phase.RUNTIME); // 워밍업
        }
        long start = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            mapper.map(event, Phase.RUNTIME);
        }
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(ms < 1000, "1000번에 " + ms + "ms");
    }
}