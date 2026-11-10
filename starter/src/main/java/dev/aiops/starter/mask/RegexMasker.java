package dev.aiops.starter.mask;

import dev.aiops.protocol.ErrorCause;
import dev.aiops.protocol.ErrorEvent;
import dev.aiops.protocol.ProtocolConstants;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Masker 구현 (부품 ⑤). 정규식을 정해진 순서로 적용한다.
 * 순서: 토큰 모양 → 이메일 → 키=값 → 긴 토큰 → 따옴표 → 길이 자르기.
 * 따옴표 규칙은 JVM이 만든 NPE 메시지 틀이면 건너뛴다 (D-094).
 * 테스트가 실패 경로를 확인할 수 있도록 final이 아니고 doMask는 패키지 공개.
 */
public class RegexMasker implements Masker {

    /** 정규식에 넣기 전에 먼저 자르는 길이. 아주 긴 메시지에서 팀 앱 스레드가 오래 멈추지 않게 */
    static final int PRE_CUT = 10_000;

    private static final int MAX = ProtocolConstants.MAX_MESSAGE_LENGTH;

    // JVM의 친절한 NPE 메시지 (JEP 358). 따옴표 안은 메서드·필드·변수 이름이지 데이터가 아니다
    private static final Pattern NPE_TEMPLATE = Pattern.compile(
            "^Cannot (?:invoke|read field|assign field|load from \\w+ array|store to \\w+ array"
                    + "|read the array length|throw exception|enter synchronized block|exit synchronized block)"
                    + "(?: \"[^\"\\r\\n]+\")? because (?:the return value of )?\"[^\"\\r\\n]+\" is null$");

    private static final Pattern JWT = Pattern.compile(
            "eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");

    private static final Pattern AIOPS_KEY = Pattern.compile("aiops_[lp]_[A-Za-z0-9_-]+");

    private static final Pattern BEARER = Pattern.compile("\\b(Bearer)\\s+[A-Za-z0-9._~+/=-]+");

    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    private static final String SECRET_KEYS = "password|passwd|pwd|client[_-]?secret|secret|access[_-]?token|access[_-]?key"
            + "|refresh[_-]?token|token|api[_-]?key";

    /** password=값, token: 값 (따옴표 없는 값). 키 이름과 구분자는 남긴다 */
    private static final Pattern KEY_VALUE = Pattern.compile(
            "\\b(" + SECRET_KEYS + ")\\b(\\s*[=:]\\s*)([^\\s,;&\"']+)", Pattern.CASE_INSENSITIVE);

    /** password="값", secret: '값' (따옴표 있는 값) */
    private static final Pattern KEY_QUOTED = Pattern.compile(
            "\\b(" + SECRET_KEYS + ")\\b(\\s*[=:]\\s*)([\"'])[^\"'\\r\\n]*\\3", Pattern.CASE_INSENSITIVE);

    /**
     * 숫자와 글자가 섞인 32자 이상 덩어리 (API 키, 해시 등).
     * 앞이 글자·숫자가 아닐 때만 시작해서 긴 단어에서도 한 번만 검사한다.
     * 숫자가 없는 긴 클래스 이름, 조각이 짧은 UUID는 걸리지 않는다
     */
    private static final Pattern LONG_TOKEN = Pattern.compile(
            "(?<![A-Za-z0-9])(?=[A-Za-z0-9]*\\d)(?=[A-Za-z0-9]*[A-Za-z])[A-Za-z0-9]{32,}(?![A-Za-z0-9])");

    private static final Pattern DOUBLE_QUOTED = Pattern.compile("\"[^\"\\r\\n]+\"");

    /** 글자 바로 뒤의 '는 따옴표가 아니다 (Can't, it's) */
    private static final Pattern SINGLE_QUOTED = Pattern.compile("(?<![A-Za-z0-9])'[^'\\r\\n]+'");

    // ---- 이벤트 단위 ----

    @Override
    public ErrorEvent mask(ErrorEvent event) {
        if (event == null) {
            return null;
        }
        try {
            List<ErrorCause> causes = new ArrayList<>();
            for (ErrorCause cause : event.causes()) {
                if (cause != null) { // record가 List.copyOf를 써서 null 원소를 받지 않는다
                    causes.add(new ErrorCause(cause.type(), maskText(cause.message())));
                }
            }
            return new ErrorEvent(event.time(), event.level(), event.exceptionType(),
                    maskText(event.message()), event.stack(), causes, event.thread(), event.phase());
        } catch (Throwable t) {
            return withoutMessages(event);
        }
    }

    /** 이벤트를 다시 만들다 실패하면 메시지와 원인을 모두 비운다. 그것도 실패하면 null (어펜더가 건너뜀) */
    private static ErrorEvent withoutMessages(ErrorEvent event) {
        try {
            return new ErrorEvent(event.time(), event.level(), event.exceptionType(),
                    "", event.stack(), List.of(), event.thread(), event.phase());
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- 문자열 단위 ----

    @Override
    public String maskText(String text) {
        if (text == null) {
            return null;
        }
        try {
            return cut(doMask(text));
        } catch (Throwable t) {
            return ""; // 유출보다 누락 (S-05)
        }
    }

    /** 실제 가리기. 테스트가 실패를 흉내 내려고 덮어쓴다 */
    String doMask(String raw) {
        String s = raw.length() > PRE_CUT ? raw.substring(0, PRE_CUT) : raw;
        boolean npe = NPE_TEMPLATE.matcher(s).matches();

        s = JWT.matcher(s).replaceAll(MASK);
        s = AIOPS_KEY.matcher(s).replaceAll(MASK);
        s = BEARER.matcher(s).replaceAll("$1 " + MASK);
        s = EMAIL.matcher(s).replaceAll(MASK + "@" + MASK);
        s = KEY_QUOTED.matcher(s).replaceAll("$1$2$3" + MASK + "$3");
        s = KEY_VALUE.matcher(s).replaceAll("$1$2" + MASK);
        s = LONG_TOKEN.matcher(s).replaceAll(MASK);

        if (!npe) {
            s = DOUBLE_QUOTED.matcher(s).replaceAll("\"" + MASK + "\"");
            s = SINGLE_QUOTED.matcher(s).replaceAll("'" + MASK + "'");
        }
        return s;
    }

    /** 가린 뒤에 자른다. 이모지 같은 두 칸짜리 글자를 반으로 쪼개지 않는다 */
    private static String cut(String s) {
        if (s.length() <= MAX) {
            return s;
        }
        int end = MAX;
        if (Character.isHighSurrogate(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }
}