package dev.aiops.starter.runtime;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * 호스트명의 SHA-256 앞 12자 (AppInfo.hostHash). 호스트명 원문은 보내지 않는다.
 * 서버 heartbeat_last.host_hash가 NOT NULL이라 절대 null을 돌려주지 않는다.
 * 5단계 EnvCollector(AI-47)도 이 클래스를 쓴다.
 */
public final class HostHash {

    private static final String UNKNOWN = "unknown-host";

    private HostHash() {
    }

    public static String get() {
        return hash(readHostName());
    }

    static String hash(String host) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(host.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6; i++) { // 6바이트 = 16진수 12자
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (Throwable t) {
            return "000000000000";
        }
    }

    private static String readHostName() {
        try {
            // 환경변수를 먼저 본다. InetAddress.getLocalHost()는 DNS 때문에 몇 초 걸리는 PC가 있다
            String host = System.getenv("COMPUTERNAME"); // 윈도우
            if (host == null || host.isBlank()) {
                host = System.getenv("HOSTNAME"); // 리눅스 셸
            }
            if (host == null || host.isBlank()) {
                host = InetAddress.getLocalHost().getHostName();
            }
            // 윈도우는 대문자, InetAddress는 소문자로 줄 수 있어서 같은 PC가 다른 해시가 되지 않게
            return host.trim().toLowerCase(Locale.ROOT);
        } catch (Throwable t) {
            return UNKNOWN;
        }
    }
}