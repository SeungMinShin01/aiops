package dev.aiops.starter.config;

/**
 * Starter 버전. 비활성 로그 "[aiops] 비활성화됨: <원인> (starter <버전>)"에 쓴다 (D-087).
 * JitPack으로 받은 jar는 MANIFEST의 Implementation-Version에서 읽고, 개발 중(IDE·Gradle
 * 실행)에는 "dev".
 */
public final class StarterVersion {

    private StarterVersion() {
    }

    public static String get() {
        try {
            String v = StarterVersion.class.getPackage().getImplementationVersion();
            return v == null ? "dev" : v;
        } catch (Throwable t) {
            return "unknown";
        }
    }
}