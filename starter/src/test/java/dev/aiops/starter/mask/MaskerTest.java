package dev.aiops.starter.mask;

import dev.aiops.protocol.ProtocolConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 마스킹 정책 테스트 (D-080 선행 테스트).
 * 구현을 바꿔서 통과시킨다. 이 표를 고치려면 PR에 이유를 적는다.
 */
class MaskerTest {

    private final Masker masker = new RegexMasker();

    // ---- 가려야 하는 것 ----

    static Stream<Arguments> 가려야_하는_것() {
        return Stream.of(
                // A. 이메일
                Arguments.of("user kim@example.com not found", "user *** not found"),
                Arguments.of("a.b+c@sub.domain.co.kr", "***"),

                // B. 키=값 / 키: 값 (키 이름은 남기고 값만)
                Arguments.of("login failed: password=1234", "login failed: password=***"),
                Arguments.of("PASSWORD = hunter2, retry", "PASSWORD = ***, retry"),
                Arguments.of("jdbc:postgresql://db/app?user=app&password=s3cr3t&ssl=true",
                        "jdbc:postgresql://db/app?user=app&password=***&ssl=true"),
                Arguments.of("secret: abc123", "secret: ***"),
                Arguments.of("api_key=xyz apiKey=xyz x-api-key: xyz", "api_key=*** apiKey=*** x-api-key: ***"),
                Arguments.of("access_token=abc&refresh_token=def", "access_token=***&refresh_token=***"),
                Arguments.of("pwd=1 passwd=2 client_secret=3", "pwd=*** passwd=*** client_secret=***"),
                Arguments.of("Invalid token: expired", "Invalid token: ***"),

                // C. 토큰 모양
                Arguments.of("Authorization: Bearer abc.def-ghi", "Authorization: Bearer ***"),
                Arguments.of("jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJl rejected", "jwt *** rejected"),
                Arguments.of("bad key aiops_l_local-dev-key-0001", "bad key ***"),
                Arguments.of("openai sk-proj-4f9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d", "openai sk-proj-***"),
                Arguments.of("sha 9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08", "sha ***"),

                // D. 따옴표 안 값
                Arguments.of("Duplicate entry 'kim' for key 'users.name'", "Duplicate entry '***' for key '***'"),
                Arguments.of("value \"abc def\" invalid", "value \"***\" invalid"),
                Arguments.of("password=\"p@ss w0rd\"", "password=\"***\""),
                Arguments.of("password=\"hunter2\"", "password=\"***\""),
                Arguments.of("User \"kim\" not found", "User \"***\" not found"),
                Arguments.of("Cannot find user \"kim\"", "Cannot find user \"***\""), // NPE 틀이 아님
                Arguments.of("{\"email\":\"kim@x.com\"}", "{\"***\":\"***\"}"),
                Arguments.of("Can't connect to 'db' because it's down", "Can't connect to '***' because it's down"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("가려야_하는_것")
    void 민감정보는_가린다(String input, String expected) {
        assertEquals(expected, masker.mask(input));
    }

    // ---- 그대로 두는 것 (디버깅에 필요한 정보) ----

    static Stream<String> 그대로_두는_것() {
        return Stream.of(
                "Connection refused: localhost/127.0.0.1:5432",
                "Can't connect to server on 127.0.0.1:3307",
                "at AbstractAutowireCapableBeanFactory.createBean",
                "order 550e8400-e29b-41d4-a716-446655440000 not found",
                "empty '' stays",
                "tokenizer=bert",
                "java.lang.IllegalStateException: Failed to load ApplicationContext",
                // JVM이 만드는 NPE 메시지 틀 (D-094): 따옴표 안이 코드 이름이라 남긴다
                "Cannot invoke \"String.length()\" because \"s\" is null",
                "Cannot read field \"name\" because \"user\" is null",
                "Cannot invoke \"com.example.Repo.find()\" because the return value of \"com.example.Svc.repo()\" is null",
                "Cannot load from int array because \"<local1>\" is null");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("그대로_두는_것")
    void 민감정보가_아니면_바꾸지_않는다(String input) {
        assertEquals(input, masker.mask(input));
    }

    // ---- null·빈 값·길이 ----

    @Test
    void null은_null() {
        assertNull(masker.mask(null));
    }

    @Test
    void 빈_문자열은_그대로() {
        assertEquals("", masker.mask(""));
    }

    @Test
    void 최대_길이로_자른다() {
        String result = masker.mask("a".repeat(5000));
        assertEquals(ProtocolConstants.MAX_MESSAGE_LENGTH, result.length());
    }

    @Test
    void 자르기_전에_가린다() {
        // 34자 토큰이 2000자 경계에 걸침. 먼저 자르면 토큰이 32자 미만이 되어 앞부분이 그대로 새어 나간다
        String input = "a".repeat(1980) + " 4f9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d";
        String result = masker.mask(input);
        assertTrue(result.length() <= ProtocolConstants.MAX_MESSAGE_LENGTH);
        assertFalse(result.contains("4f9a8b7c"), "토큰 일부가 남음");
    }

    // ---- 실패 시 빈 메시지 ----

    @Test
    void 내부에서_예외가_나면_빈_문자열() {
        RegexMasker broken = new RegexMasker() {
            @Override
            String doMask(String raw) {
                throw new IllegalStateException("boom");
            }
        };
        assertEquals("", broken.mask("password=1234"));
    }

    @Test
    void 내부에서_Error가_나도_빈_문자열() {
        RegexMasker broken = new RegexMasker() {
            @Override
            String doMask(String raw) {
                throw new StackOverflowError();
            }
        };
        assertEquals("", broken.mask("password=1234"));
    }

    // ---- 속도 (팀 앱 스레드에서 돌기 때문에) ----

    @Test
    void 백만_글자도_빨리_끝난다() {
        String huge = "x@".repeat(500_000);
        long start = System.nanoTime();
        masker.mask(huge);
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(ms < 500, "걸린 시간: " + ms + "ms");
    }

    @Test
    void 보통_메시지_만_번도_1초_안에_끝난다() {
        String msg = "login failed for kim@example.com: password=1234, token=abc";
        long start = System.nanoTime();
        for (int i = 0; i < 10_000; i++) {
            masker.mask(msg);
        }
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(ms < 1000, "걸린 시간: " + ms + "ms");
    }
}