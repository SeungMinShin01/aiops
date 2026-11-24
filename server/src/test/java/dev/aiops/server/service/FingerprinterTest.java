package dev.aiops.server.service;

import dev.aiops.protocol.StackFrame;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 설계자 선행 테스트 (D-080). 담당자는 이 파일을 수정하지 않는다 (규칙 9).
 * Fingerprinter.fingerprint(String exceptionType, List<StackFrame> frames)를 구현해
 * 전부 초록으로.
 * 규칙: 예외 타입 + 팀 코드 상위 3프레임(클래스#메서드, 줄 번호 제외)을 "|"로 이어 SHA-256 hex 소문자. 명세: Jira
 * AI-51
 */
class FingerprinterTest {

    private static final String NPE = "java.lang.NullPointerException";

    private final Fingerprinter fingerprinter = new Fingerprinter();

    // ---- 기대값을 만드는 도우미 (명세 6번의 "문자열 → SHA-256 hex"를 그대로 옮김) ----

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static StackFrame f(String className, String method, int line) {
        String file = className.substring(className.lastIndexOf('.') + 1) + ".java";
        return new StackFrame(className, method, file, line);
    }

    // ---- 케이스 ----

    @Test
    void 줄_번호만_다르면_같은_지문() {
        List<StackFrame> v1 = List.of(f("dev.shop.OrderService", "place", 42),
                f("dev.shop.OrderController", "order", 10));
        List<StackFrame> v2 = List.of(f("dev.shop.OrderService", "place", 57),
                f("dev.shop.OrderController", "order", -1));

        assertThat(fingerprinter.fingerprint(NPE, v1)).isEqualTo(fingerprinter.fingerprint(NPE, v2));
    }

    @Test
    void 프레임워크_프레임은_건너뛰고_팀_프레임_상위_3개만_쓴다() {
        List<StackFrame> frames = List.of(
                f("org.springframework.web.servlet.DispatcherServlet", "doDispatch", 1),
                f("dev.shop.A", "a", 1),
                f("java.lang.reflect.Method", "invoke", 1),
                f("dev.shop.B", "b", 1),
                f("com.zaxxer.hikari.pool.HikariPool", "getConnection", 1),
                f("dev.shop.C", "c", 1),
                f("dev.shop.D", "d", 1), // 4번째 팀 프레임은 쓰지 않는다
                f("org.hibernate.engine.Loader", "load", 1));

        assertThat(fingerprinter.fingerprint(NPE, frames))
                .isEqualTo(sha256(NPE + "|dev.shop.A#a|dev.shop.B#b|dev.shop.C#c"));
    }

    @Test
    void 접두사_10개는_모두_프레임워크로_본다() {
        List<StackFrame> frames = List.of(
                f("java.util.ArrayList", "get", 1),
                f("javax.naming.Ctx", "lookup", 1),
                f("jakarta.servlet.http.HttpServlet", "service", 1),
                f("jdk.internal.reflect.Method", "invoke", 1),
                f("sun.reflect.Accessor", "invoke", 1),
                f("org.springframework.aop.Proxy", "invoke", 1),
                f("org.hibernate.Session", "find", 1),
                f("com.zaxxer.hikari.HikariDataSource", "getConnection", 1),
                f("org.apache.catalina.Valve", "invoke", 1),
                f("dev.aiops.starter.capture.AiopsLogbackAppender", "append", 1),
                f("dev.shop.Only", "run", 1));

        assertThat(fingerprinter.fingerprint(NPE, frames))
                .isEqualTo(sha256(NPE + "|dev.shop.Only#run"));
    }

    @Test
    void 팀_프레임이_하나도_없으면_전체_상위_3개를_쓴다() {
        List<StackFrame> frames = List.of(
                f("java.util.ArrayList", "get", 427),
                f("org.springframework.util.Assert", "notNull", 12),
                f("jakarta.servlet.FilterChain", "doFilter", 99),
                f("org.apache.catalina.Valve", "invoke", 5));

        assertThat(fingerprinter.fingerprint(NPE, frames))
                .isEqualTo(sha256(NPE
                        + "|java.util.ArrayList#get|org.springframework.util.Assert#notNull|jakarta.servlet.FilterChain#doFilter"));
    }

    @Test
    void 팀_프레임이_1개면_그것만_쓴다() {
        List<StackFrame> frames = List.of(
                f("org.springframework.web.Handler", "handle", 1),
                f("dev.shop.PayService", "pay", 88),
                f("java.lang.Thread", "run", 1));

        assertThat(fingerprinter.fingerprint(NPE, frames))
                .isEqualTo(sha256(NPE + "|dev.shop.PayService#pay"));
    }

    @Test
    void 예외_타입이_다르면_다른_지문() {
        List<StackFrame> frames = List.of(f("dev.shop.OrderService", "place", 42));

        assertThat(fingerprinter.fingerprint(NPE, frames))
                .isNotEqualTo(fingerprinter.fingerprint("java.lang.IllegalArgumentException", frames));
    }

    @Test
    void 스택이_비어_있으면_예외_타입만으로_계산한다() {
        assertThat(fingerprinter.fingerprint(NPE, List.of())).isEqualTo(sha256(NPE));
    }

    @Test
    void 결과는_64자_hex_소문자() {
        String result = fingerprinter.fingerprint(NPE, List.of(f("dev.shop.A", "a", 1)));

        assertThat(result).matches("[0-9a-f]{64}");
    }

    @Test
    void 같은_입력은_항상_같은_결과() {
        List<StackFrame> frames = List.of(f("dev.shop.A", "a", 1), f("dev.shop.B", "b", 2));

        assertThat(fingerprinter.fingerprint(NPE, frames)).isEqualTo(fingerprinter.fingerprint(NPE, frames));
    }
}