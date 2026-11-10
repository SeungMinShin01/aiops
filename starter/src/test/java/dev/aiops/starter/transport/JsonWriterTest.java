package dev.aiops.starter.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aiops.protocol.AppInfo;
import dev.aiops.protocol.ConfigKey;
import dev.aiops.protocol.ConfigSource;
import dev.aiops.protocol.Dependency;
import dev.aiops.protocol.ErrorCause;
import dev.aiops.protocol.ErrorEvent;
import dev.aiops.protocol.EventBatch;
import dev.aiops.protocol.Heartbeat;
import dev.aiops.protocol.HelloRequest;
import dev.aiops.protocol.Phase;
import dev.aiops.protocol.StackFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** JsonWriter로 쓴 것을 Jackson(테스트에만 있음)으로 다시 읽어 원래 record와 같은지 본다 */
class JsonWriterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static AppInfo app() {
        return new AppInfo("sample-app", "abc1234", Boolean.TRUE, "0123456789ab");
    }

    @Test
    void EventBatch_왕복() throws Exception {
        ErrorEvent event = new ErrorEvent(
                1_730_000_000_000L, "ERROR", "java.lang.NullPointerException", "user is null",
                List.of(new StackFrame("dev.aiops.sample.BoomController", "npe", "BoomController.java", 42),
                        new StackFrame("jdk.internal.reflect.Method", "invoke", null, -1)),
                List.of(new ErrorCause("java.lang.IllegalStateException", "inner")),
                "http-nio-8081-exec-1", Phase.RUNTIME);
        EventBatch original = new EventBatch("local", app(), List.of(event), 3);

        EventBatch back = mapper.readValue(JsonWriter.write(original), EventBatch.class);

        assertEquals(original, back);
    }

    @Test
    void HelloRequest_왕복() throws Exception {
        HelloRequest original = new HelloRequest(
                "prod", app(), "17.0.12", "4.1.1",
                List.of(new Dependency("org.postgresql", "postgresql", "42.7.4"),
                        new Dependency("com.example", "no-version", null)),
                List.of(new ConfigKey("spring.datasource.url", ConfigSource.YML),
                        new ConfigKey("AIOPS_KEY", ConfigSource.ENV)));

        HelloRequest back = mapper.readValue(JsonWriter.write(original), HelloRequest.class);

        assertEquals(original, back);
    }

    @Test
    void Heartbeat는_필드_순서대로_쓴다() {
        assertEquals("{\"env\":\"local\",\"app\":null,\"sentAt\":123}",
                JsonWriter.write(new Heartbeat("local", null, 123L)));
    }

    @Test
    void null_필드와_빈_리스트() throws Exception {
        EventBatch original = new EventBatch("local", new AppInfo("a", null, null, "h"), List.of(), 0);

        String json = JsonWriter.write(original);

        assertTrue(json.contains("\"commit\":null"), json);
        assertTrue(json.contains("\"dirty\":null"), json);
        assertTrue(json.contains("\"events\":[]"), json);
        assertEquals(original, mapper.readValue(json, EventBatch.class));
    }

    static Stream<Arguments> escapes() {
        return Stream.of(
                Arguments.of("따옴표", "a\"b", "\"a\\\"b\""),
                Arguments.of("백슬래시", "C:\\temp", "\"C:\\\\temp\""),
                Arguments.of("줄바꿈", "a\nb", "\"a\\nb\""),
                Arguments.of("복귀", "a\rb", "\"a\\rb\""),
                Arguments.of("탭", "a\tb", "\"a\\tb\""),
                Arguments.of("한글", "사용자 없음", "\"사용자 없음\""),
                Arguments.of("이모지", "fire \uD83D\uDD25", "\"fire \uD83D\uDD25\""),
                Arguments.of("제어문자", "a\u0001b", "\"a\\u0001b\""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("escapes")
    void 이스케이프(String name, String input, String expected) throws Exception {
        String json = JsonWriter.write(input);

        assertEquals(expected, json);
        assertEquals(input, mapper.readValue(json, String.class));
    }

    @Test
    void 지원하지_않는_타입은_IllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> JsonWriter.write(new Object()));
    }
}