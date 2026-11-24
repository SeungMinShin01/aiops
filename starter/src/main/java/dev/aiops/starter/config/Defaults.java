package dev.aiops.starter.config;

import java.time.Duration;

/** Starter 기본값. 팀이 설정하지 않으면 이 값을 쓴다 */
public final class Defaults {

    /**
     * 수집 서버 주소. 도메인 확정 전이라 임시 값 (설계 문서 열린 질문).
     * .invalid는 실제로 연결되지 않는 예약 도메인이라, 설정을 빠뜨려도 어딘가로 잘못 전송되지 않는다.
     * 로컬 개발에서는 aiops.endpoint=http://localhost:8080 으로 덮어쓴다.
     */
    public static final String ENDPOINT = "https://ingest.aiops.invalid";

    public static final int BUFFER_CAPACITY = 1_000;
    public static final int BUFFER_CAPACITY_MIN = 100;
    public static final int BUFFER_CAPACITY_MAX = 10_000;

    public static final Duration FLUSH_INTERVAL = Duration.ofSeconds(5);
    public static final Duration FLUSH_INTERVAL_MIN = Duration.ofSeconds(1);
    public static final Duration FLUSH_INTERVAL_MAX = Duration.ofSeconds(60);

    public static final String UNKNOWN_APP = "unknown";

    private Defaults() {
    }
}