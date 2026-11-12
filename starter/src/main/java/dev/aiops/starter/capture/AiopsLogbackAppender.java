package dev.aiops.starter.capture;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import dev.aiops.protocol.ErrorEvent;
import dev.aiops.protocol.Phase;
import dev.aiops.starter.mask.Masker;
import dev.aiops.starter.transport.EventBuffer;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 부품 ③. 팀 앱 Logback에 붙어 ERROR + 예외 로그만 골라 Mapper → Masker → Buffer로 넘긴다.
 * log.error()를 부른 팀 앱 스레드에서 그대로 돈다 → 대기·네트워크·파일 금지, 예외를 절대 밖으로 내지 않는다.
 * UnsynchronizedAppenderBase: AppenderBase는 synchronized라 모든 스레드가 한 줄로 서서 기다린다
 * (D-007).
 */
public final class AiopsLogbackAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    /** 장착 확인용 고정 이름 (AI-43 attachAppenderIfMissing이 이 이름으로 찾는다) */
    public static final String NAME = "AIOPS";

    /**
     * 무시할 로거 (D-087). 우리 자신의 로그, 우리가 쓰는 JDK HttpClient가 전송 실패 때 찍는 로그를
     * 다시 잡으면 "잡기 → 전송 실패 → 로그 → 잡기" 루프가 된다.
     * 점 단위로 비교한다: "dev.aiops"와 "dev.aiops.*"는 무시, "dev.aiopsshop.*"은 잡는다.
     */
    static final String[] IGNORED_LOGGER_PREFIXES = { "dev.aiops", "jdk.internal.httpclient", "java.net.http" };

    private static final String[] IGNORED_WITH_DOT = withDot(IGNORED_LOGGER_PREFIXES);

    private final EventMapper mapper;
    private final Masker masker;
    private final EventBuffer buffer;

    /** 버퍼에 들어간 개수 (테스트·진단용, 로그로 찍지 않는다) */
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicBoolean errorReported = new AtomicBoolean();

    public AiopsLogbackAppender(EventMapper mapper, Masker masker, EventBuffer buffer) {
        this.mapper = mapper;
        this.masker = masker;
        this.buffer = buffer;
        setName(NAME);
    }

    @Override
    protected void append(ILoggingEvent event) {
        try {
            if (!isTarget(event)) {
                return;
            }
            ErrorEvent mapped = mapper.map(event, Phase.RUNTIME);
            if (mapped == null) {
                return;
            }
            ErrorEvent masked = masker.mask(mapped);
            if (masked == null) {
                return;
            }
            if (buffer.offer(masked)) { // 꽉 차서 false면 버퍼가 버린 개수를 센다
                accepted.incrementAndGet();
            }
        } catch (Throwable t) {
            reportOnce(t);
        }
    }

    long acceptedCount() {
        return accepted.get();
    }

    static boolean isTarget(ILoggingEvent event) {
        if (event == null || event.getLevel() == null) {
            return false;
        }
        if (event.getLevel().toInt() < Level.ERROR_INT) {
            return false; // INFO·WARN은 범위 밖
        }
        if (event.getThrowableProxy() == null) {
            return false; // 예외 없는 ERROR는 범위 밖
        }
        return !isIgnoredLogger(event.getLoggerName());
    }

    static boolean isIgnoredLogger(String loggerName) {
        if (loggerName == null) {
            return false;
        }
        for (int i = 0; i < IGNORED_LOGGER_PREFIXES.length; i++) {
            if (loggerName.equals(IGNORED_LOGGER_PREFIXES[i]) || loggerName.startsWith(IGNORED_WITH_DOT[i])) {
                return true;
            }
        }
        return false;
    }

    /**
     * 처음 한 번만 Logback 상태(status)에 남긴다. 매번 남기면 팀 콘솔이 우리 메시지로 덮인다.
     * 예외 메시지는 넣지 않는다 (민감정보가 있을 수 있다).
     */
    private void reportOnce(Throwable t) {
        if (errorReported.compareAndSet(false, true)) {
            try {
                addError("[aiops] 에러 수집 중 오류, 이후 같은 오류는 기록하지 않음: " + t.getClass().getName());
            } catch (Throwable ignored) {
                // 상태 기록마저 실패해도 팀 앱에는 영향을 주지 않는다
            }
        }
    }

    private static String[] withDot(String[] prefixes) {
        String[] out = new String[prefixes.length];
        for (int i = 0; i < prefixes.length; i++) {
            out[i] = prefixes[i] + ".";
        }
        return out;
    }
}