package dev.aiops.starter.mask;

import dev.aiops.protocol.ErrorCause;
import dev.aiops.protocol.ErrorEvent;
import dev.aiops.protocol.Phase;
import dev.aiops.protocol.ProtocolConstants;
import dev.aiops.protocol.StackFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * 🔴 설계자 선행 테스트 (D-080). 담당자는 이 파일을 수정하지 않는다 (규칙 9).
 * Masker / RegexMasker를 구현해서 전부 초록으로 만든다. 명세: Jira AI-41, 설계 문서 부품 ⑤
 */
class MaskerTest {

    private final Masker masker = new RegexMasker();

    // ---- maskText: 입력 → 기대값 표 ----

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("maskCases")
    void maskText_규칙표(String 설명, String input, String expected) {
        assertEquals(expected, masker.maskText(input));
    }

    static Stream<Arguments> maskCases() {
        return Stream.of(
                arguments("이메일",
                        "user hong@example.com not found",
                        "user ***@*** not found"),
                arguments("Bearer 토큰",
                        "auth failed with Bearer abc123.def456",
                        "auth failed with Bearer ***"),
                arguments("Basic 토큰 (방식 이름은 남김)",
                        "auth failed with Basic dXNlcjpwYXNz",
                        "auth failed with Basic ***"),
                arguments("password=값",
                        "login failed password=1234",
                        "login failed password=***"),
                arguments("대소문자 무시, 콜론+공백 구분자",
                        "PASSWORD: hunter2",
                        "PASSWORD: ***"),
                arguments("api_key=값",
                        "api_key=xyz",
                        "api_key=***"),
                arguments("JSON 큰따옴표 값",
                        "{\"secret\": \"abc def\"}",
                        "{\"secret\": \"***\"}"),
                arguments("작은따옴표 값",
                        "token='abc def'",
                        "token='***'"),
                arguments("JDBC URL의 password 파라미터",
                        "jdbc:postgresql://db:5432/app?user=app&password=s3cret",
                        "jdbc:postgresql://db:5432/app?user=app&password=***"),
                arguments("32자 이상 긴 토큰 (40자)",
                        "key is AbCdEfGhIjKlMnOpQrStUvWxYz0123456789abcd",
                        "key is ***"),
                arguments("평범한 문장은 그대로",
                        "Cannot invoke \"String.length()\" because \"s\" is null",
                        "Cannot invoke \"String.length()\" because \"s\" is null"));
    }

    @Test
    void null은_null_그대로() {
        assertNull(masker.maskText(null));
    }

    @Test
    void 최대_길이를_넘으면_잘라내고_표시한다() {
        String input = "hello world ".repeat(500); // 6,000자

        String result = masker.maskText(input);

        assertTrue(result.length() <= ProtocolConstants.MAX_MESSAGE_LENGTH,
                "결과 길이 " + result.length() + "가 최대 길이를 넘음");
        assertTrue(result.endsWith("…(truncated)"));
        assertTrue(result.startsWith("hello world hello world"));
    }

    @Test
    void 만_자_입력도_빠르게_처리한다() {
        String input = "user hong@example.com password=1234 retry ".repeat(240); // 약 10,000자

        // 명세 목표는 10ms. CI 편차를 고려해 100ms로 검사한다. 넘으면 정규식 백트래킹을 의심할 것
        assertTimeout(Duration.ofMillis(100), () -> masker.maskText(input));
    }

    // ---- mask(ErrorEvent): 메시지·원인 메시지만 바뀌고 나머지는 그대로 ----

    @Test
    void 이벤트의_메시지와_원인_메시지를_가린다() {
        List<StackFrame> stack = List.of(new StackFrame("dev.shop.LoginService", "login", "LoginService.java", 42));
        List<ErrorCause> causes = List.of(
                new ErrorCause("java.sql.SQLException", "password=1234 rejected"),
                new ErrorCause("java.net.SocketException", null));
        ErrorEvent original = new ErrorEvent(
                1_700_000_000_000L, "ERROR", "java.lang.IllegalStateException",
                "login failed for hong@example.com", stack, causes, "http-nio-8080-exec-1", Phase.RUNTIME);

        ErrorEvent masked = masker.mask(original);

        assertEquals("login failed for ***@***", masked.message());
        assertEquals("password=*** rejected", masked.causes().get(0).message());
        assertNull(masked.causes().get(1).message());

        // 타입·프레임·나머지 필드는 그대로
        assertEquals("java.sql.SQLException", masked.causes().get(0).type());
        assertEquals(stack, masked.stack());
        assertEquals(original.time(), masked.time());
        assertEquals(original.level(), masked.level());
        assertEquals(original.exceptionType(), masked.exceptionType());
        assertEquals(original.thread(), masked.thread());
        assertEquals(original.phase(), masked.phase());
    }
}