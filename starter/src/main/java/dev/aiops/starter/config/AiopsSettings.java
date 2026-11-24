package dev.aiops.starter.config;

import dev.aiops.protocol.ProtocolConstants;
import org.springframework.core.env.Environment;

import java.time.Duration;
import java.util.Locale;

/**
 * 팀 앱의 aiops.* 설정 (부품 ①). Bean이 생기기 전(로깅 초기화 직후)에 읽어야 하므로
 * 
 * @ConfigurationProperties를 쓰지 않고 Environment에서 직접 읽는다
 *                           필드 7개는 고정 — 다른 부품이 이 이름으로 읽는다
 */
public record AiopsSettings(
        String apiKey,
        String env,
        String endpoint,
        boolean enabled,
        int bufferCapacity,
        Duration flushInterval,
        String appName) {

    /**
     * 설정을 읽는다. Starter를 꺼야 하면 null.
     * - aiops.enabled=false → null (조용히)
     * - aiops.api-key 없음 → 경고 한 줄 후 null
     * 어떤 예외·Error도 밖으로 내보내지 않는다 (규칙 4, D-087).
     */
    public static AiopsSettings fromOrNull(Environment environment) {
        try {
            String enabled = environment.getProperty("aiops.enabled");
            if (enabled != null && enabled.trim().equalsIgnoreCase("false")) {
                return null;
            }

            String apiKey = trimToNull(environment.getProperty("aiops.api-key"));
            if (apiKey == null) {
                // 로거는 아직 초기화 중일 수 있어서 System.err (AI-35)
                System.err.println("[aiops] aiops.api-key 없음, 비활성");
                return null;
            }

            return new AiopsSettings(
                    apiKey,
                    readEnv(environment.getProperty("aiops.env")),
                    readEndpoint(environment.getProperty("aiops.endpoint")),
                    true,
                    readBufferCapacity(environment.getProperty("aiops.buffer-capacity")),
                    readFlushInterval(environment.getProperty("aiops.flush-interval")),
                    readAppName(environment.getProperty("spring.application.name")));
        } catch (Throwable t) {
            // 원인을 남겨야 팀이 왜 꺼졌는지 안다 (D-087). 키 원문은 메시지에 없다
            System.err.println("[aiops] 비활성화됨: " + t.getClass().getName() + " " + t.getMessage()
                    + " (starter " + StarterVersion.get() + ")");
            return null;
        }
    }

    /** 키 원문을 로그에 남기지 않는다 (규칙 10) */
    @Override
    public String toString() {
        return "AiopsSettings[apiKey=***, env=" + env + ", endpoint=" + endpoint
                + ", enabled=" + enabled + ", bufferCapacity=" + bufferCapacity
                + ", flushInterval=" + flushInterval + ", appName=" + appName + "]";
    }

    // ---- 값 하나씩 읽기. 잘못된 값은 기본값으로 ----

    private static String readEnv(String raw) {
        String v = trimToNull(raw);
        if (v == null) {
            return ProtocolConstants.ENV_LOCAL;
        }
        v = v.toLowerCase(Locale.ROOT);
        if (v.equals(ProtocolConstants.ENV_LOCAL) || v.equals(ProtocolConstants.ENV_PROD)) {
            return v;
        }
        System.err.println("[aiops] aiops.env는 local 또는 prod만 가능, local로 사용");
        return ProtocolConstants.ENV_LOCAL;
    }

    private static String readEndpoint(String raw) {
        String v = trimToNull(raw);
        if (v == null) {
            return Defaults.ENDPOINT;
        }
        while (v.endsWith("/")) { // "http://x/" + "/v1/events" 처럼 슬래시가 겹치지 않게
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    private static int readBufferCapacity(String raw) {
        try {
            int v = Integer.parseInt(raw.trim());
            if (v >= Defaults.BUFFER_CAPACITY_MIN && v <= Defaults.BUFFER_CAPACITY_MAX) {
                return v;
            }
        } catch (RuntimeException ignored) {
            // null·숫자 아님 → 기본값
        }
        return Defaults.BUFFER_CAPACITY;
    }

    /** "5", "5s", "500ms", "PT5S" 모두 허용. 1~60초 밖이면 기본 5초 */
    private static Duration readFlushInterval(String raw) {
        try {
            Duration d = parseDuration(raw.trim().toLowerCase(Locale.ROOT));
            if (d.compareTo(Defaults.FLUSH_INTERVAL_MIN) >= 0 && d.compareTo(Defaults.FLUSH_INTERVAL_MAX) <= 0) {
                return d;
            }
        } catch (RuntimeException ignored) {
            // null·형식 오류 → 기본값
        }
        return Defaults.FLUSH_INTERVAL;
    }

    private static Duration parseDuration(String v) {
        if (v.endsWith("ms")) {
            return Duration.ofMillis(Long.parseLong(v.substring(0, v.length() - 2)));
        }
        if (v.endsWith("s") && !v.startsWith("pt")) {
            return Duration.ofSeconds(Long.parseLong(v.substring(0, v.length() - 1)));
        }
        if (v.startsWith("pt")) {
            return Duration.parse(v.toUpperCase(Locale.ROOT));
        }
        return Duration.ofSeconds(Long.parseLong(v)); // 숫자만 → 초
    }

    private static String readAppName(String raw) {
        String v = trimToNull(raw);
        return v == null ? Defaults.UNKNOWN_APP : v;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}