package dev.aiops.starter.runtime;

import dev.aiops.protocol.AppInfo;
import dev.aiops.starter.capture.DefaultEventMapper;
import dev.aiops.starter.capture.EventMapper;
import dev.aiops.starter.config.AiopsSettings;
import dev.aiops.starter.config.StarterVersion;
import dev.aiops.starter.mask.Masker;
import dev.aiops.starter.mask.RegexMasker;
import dev.aiops.starter.transport.BatchingEventSender;
import dev.aiops.starter.transport.BoundedEventBuffer;
import dev.aiops.starter.transport.EventBuffer;
import dev.aiops.starter.transport.EventSender;
import dev.aiops.starter.transport.HttpTransport;
import dev.aiops.starter.transport.Transport;
import org.springframework.core.env.Environment;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 부품 ②. 설정 → 버퍼 → 전송 → 전송 스레드를 연결하고, 어펜더를 Logback에 붙인다.
 * 부품은 JVM당 1번만 만들고(D-006), 어펜더 장착은 부를 때마다 확인한다.
 * 한 번 비활성화되면 이 JVM에서는 다시 켜지지 않는다.
 */
public final class AiopsRuntime {

    private static final Object LOCK = new Object();
    private static volatile AiopsRuntime instance; // 다른 스레드(어펜더)도 바로 보이게 volatile
    private static final AtomicBoolean DISABLED = new AtomicBoolean();

    private final AiopsSettings settings;
    private final EventBuffer buffer;
    private final EventSender sender;
    private final EventMapper mapper;
    private final Masker masker;

    private AiopsRuntime(AiopsSettings settings, EventBuffer buffer, EventSender sender,
            EventMapper mapper, Masker masker) {
        this.settings = settings;
        this.buffer = buffer;
        this.sender = sender;
        this.mapper = mapper;
        this.masker = masker;
    }

    /** 없으면 만들고 전송 스레드를 시작한다. 꺼야 하면(키 없음·enabled=false·비활성·내부 오류) null */
    public static AiopsRuntime startIfAbsent(Environment environment) {
        if (DISABLED.get()) {
            return null;
        }
        AiopsRuntime current = instance;
        if (current != null) {
            return current;
        }
        synchronized (LOCK) {
            if (instance != null) { // 기다리는 사이 다른 스레드가 만들었으면
                return instance;
            }
            try {
                AiopsSettings settings = AiopsSettings.fromOrNull(environment);
                if (settings == null) {
                    return null; // 경고는 AiopsSettings가 이미 찍음
                }
                EventBuffer buffer = new BoundedEventBuffer(settings.bufferCapacity());
                Transport transport = new HttpTransport(settings);
                AppInfo appInfo = new AppInfo(settings.appName(), null, null, HostHash.get());
                EventSender sender = new BatchingEventSender(buffer, transport, settings, appInfo,
                        Clock.systemUTC());
                sender.start();
                instance = new AiopsRuntime(settings, buffer, sender, new DefaultEventMapper(), new RegexMasker());
                return instance;
            } catch (Throwable t) {
                disable(t);
                return null;
            }
        }
    }

    /**
     * 어펜더가 루트 로거에 없으면 붙인다 (AI-43). 로깅 초기화·DevTools 재시작이 떼어 버려도
     * 다음 호출에서 다시 붙는다. 버퍼·전송 스레드는 새로 만들지 않는다.
     */
    public static void attachAppenderIfMissing() {
        AiopsRuntime r = instance;
        if (r == null || DISABLED.get()) {
            return;
        }
        synchronized (LOCK) {
            LogbackAttacher.attachIfMissing(r.mapper, r.masker, r.buffer);
        }
    }

    /**
     * Starter만 끈다 (D-087): 어펜더 떼기, 전송 스레드 멈추기, System.err에 한 줄.
     * 여러 번 불려도 한 번만 동작한다. 팀 앱에는 아무 영향이 없어야 하므로 여기서도 아무것도 던지지 않는다.
     */
    public static void disable(Throwable cause) {
        if (!DISABLED.compareAndSet(false, true)) {
            return;
        }
        synchronized (LOCK) {
            try {
                LogbackAttacher.detach();
            } catch (Throwable ignored) {
                // Logback이 없으면 뗄 것도 없다
            }
            AiopsRuntime r = instance;
            instance = null;
            if (r != null) {
                try {
                    r.sender.stop();
                } catch (Throwable ignored) {
                    // 멈추기 실패도 팀 앱에는 영향 없음
                }
            }
        }
        System.err.println(disabledLine(cause));
    }

    public static boolean isDisabled() {
        return DISABLED.get();
    }

    static String disabledLine(Throwable cause) {
        String type = cause == null ? "unknown" : cause.getClass().getName();
        String message = cause == null ? "" : String.valueOf(cause.getMessage());
        return "[aiops] 비활성화됨: " + type + " " + message + " (starter " + StarterVersion.get() + ")";
    }

    /** 아직 시작 안 했거나 꺼져 있으면 null */
    public static AiopsRuntime current() {
        return instance;
    }

    public AiopsSettings settings() {
        return settings;
    }

    public EventBuffer buffer() {
        return buffer;
    }

    public EventSender sender() {
        return sender;
    }

    /** 시작 실패(AI-45)가 Logback 없이 예외를 바꿀 때 쓴다 */
    public EventMapper mapper() {
        return mapper;
    }

    public Masker masker() {
        return masker;
    }

    /** 테스트 전용: 어펜더를 떼고, 스레드를 멈추고, 비활성 상태도 되돌린다 */
    static void resetForTest() {
        synchronized (LOCK) {
            try {
                LogbackAttacher.detach();
            } catch (Throwable ignored) {
                // 테스트 정리
            }
            if (instance != null) {
                instance.sender.stop();
                instance = null;
            }
            DISABLED.set(false);
        }
    }
}