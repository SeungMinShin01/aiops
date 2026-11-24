package dev.aiops.starter.capture;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import dev.aiops.protocol.ErrorEvent;
import dev.aiops.protocol.Phase;
import dev.aiops.starter.mask.RegexMasker;
import dev.aiops.starter.transport.EventBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AiopsLogbackAppenderTest {

    private static final LoggerContext CONTEXT = new LoggerContext();

    /** 넣은 것을 리스트에 모으는 가짜 버퍼 */
    static class ListBuffer implements EventBuffer {
        final List<ErrorEvent> events = new ArrayList<>();

        @Override
        public boolean offer(ErrorEvent event) {
            events.add(event);
            return true;
        }

        @Override
        public List<ErrorEvent> drain(int max) {
            return List.of();
        }

        @Override
        public int takeDroppedCount() {
            return 0;
        }

        @Override
        public int size() {
            return events.size();
        }
    }

    private ListBuffer buffer;
    private AiopsLogbackAppender appender;

    @BeforeEach
    void setUp() {
        buffer = new ListBuffer();
        appender = started(new AiopsLogbackAppender(new DefaultEventMapper(), new RegexMasker(), buffer));
    }

    private static AiopsLogbackAppender started(AiopsLogbackAppender a) {
        a.setContext(CONTEXT);
        a.start();
        return a;
    }

    private static ILoggingEvent log(String loggerName, Level level, String message, Throwable t) {
        Logger logger = CONTEXT.getLogger(loggerName);
        return new LoggingEvent(Logger.class.getName(), logger, level, message, t, null);
    }

    private static final String TEAM_LOGGER = "com.example.OrderService";

    // ---- 잡는 것 ----

    @Test
    void ERROR에_예외가_붙으면_버퍼에_1개() {
        appender.doAppend(log(TEAM_LOGGER, Level.ERROR, "주문 실패", new IllegalStateException("boom")));

        assertEquals(1, buffer.events.size());
        ErrorEvent e = buffer.events.get(0);
        assertEquals("java.lang.IllegalStateException", e.exceptionType());
        assertEquals(Phase.RUNTIME, e.phase());
        assertEquals(TEAM_LOGGER, e.loggerName());
        assertEquals(1, appender.acceptedCount());
    }

    @Test
    void 버퍼에는_마스킹된_값이_들어간다() {
        appender.doAppend(log(TEAM_LOGGER, Level.ERROR, "x", new RuntimeException("login failed: password=1234")));

        assertEquals("login failed: password=***", buffer.events.get(0).message());
    }

    @Test
    void 이름이_비슷한_팀_패키지는_무시하지_않는다() {
        // "dev.aiops"로 시작하지만 우리 패키지가 아니다 (점 단위로 비교)
        appender.doAppend(log("dev.aiopsshop.OrderService", Level.ERROR, "x", new RuntimeException("boom")));

        assertEquals(1, buffer.events.size());
    }

    // ---- 버리는 것 ----

    @Test
    void INFO_WARN은_예외가_있어도_버린다() {
        appender.doAppend(log(TEAM_LOGGER, Level.INFO, "x", new RuntimeException("boom")));
        appender.doAppend(log(TEAM_LOGGER, Level.WARN, "x", new RuntimeException("boom")));

        assertTrue(buffer.events.isEmpty());
    }

    @Test
    void 예외가_없는_ERROR는_버린다() {
        appender.doAppend(log(TEAM_LOGGER, Level.ERROR, "그냥 에러 메시지", null));

        assertTrue(buffer.events.isEmpty());
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "dev.aiops", // 우리 자신
            "dev.aiops.starter.transport.HttpTransport",
            "jdk.internal.httpclient.debug", // 우리가 쓰는 HttpClient의 내부 로그
            "java.net.http.HttpClient"
    })
    void 무시_목록_로거는_버린다(String loggerName) {
        appender.doAppend(log(loggerName, Level.ERROR, "x", new RuntimeException("boom")));

        assertTrue(buffer.events.isEmpty());
    }

    // ---- 절대 터지지 않는다 ----

    @Test
    void Mapper가_예외를_던져도_조용히_넘어간다() {
        EventMapper broken = new EventMapper() {
            @Override
            public ErrorEvent map(ILoggingEvent event, Phase phase) {
                throw new IllegalStateException("mapper boom");
            }

            @Override
            public ErrorEvent mapThrowable(Throwable throwable, Phase phase) {
                return null;
            }
        };
        AiopsLogbackAppender a = started(new AiopsLogbackAppender(broken, new RegexMasker(), buffer));

        assertDoesNotThrow(() -> a.doAppend(log(TEAM_LOGGER, Level.ERROR, "x", new RuntimeException("boom"))));
        assertTrue(buffer.events.isEmpty());
    }

    @Test
    void Error가_나도_조용히_넘어간다() {
        EventBuffer exploding = new ListBuffer() {
            @Override
            public boolean offer(ErrorEvent event) {
                throw new StackOverflowError();
            }
        };
        AiopsLogbackAppender a = started(
                new AiopsLogbackAppender(new DefaultEventMapper(), new RegexMasker(), exploding));

        assertDoesNotThrow(() -> a.doAppend(log(TEAM_LOGGER, Level.ERROR, "x", new RuntimeException("boom"))));
        assertDoesNotThrow(() -> a.doAppend(log(TEAM_LOGGER, Level.ERROR, "x", new RuntimeException("boom"))));
        assertEquals(0, a.acceptedCount());
    }

    @Test
    void 이름은_AIOPS() {
        assertEquals("AIOPS", appender.getName());
        assertEquals(AiopsLogbackAppender.NAME, appender.getName());
    }
}