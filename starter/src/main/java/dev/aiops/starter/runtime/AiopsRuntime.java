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
import dev.aiops.protocol.ErrorEvent;
import dev.aiops.protocol.Phase;
import java.time.Duration;
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

    /** 시작 실패를 보낼 때 기다리는 최대 시간. 앱 종료를 우리 때문에 늦추지 않는다 (늘리지 않는다) */
    static final Duration STARTUP_FAILURE_WAIT = Duration.ofSeconds(3);

    /** 종료할 때 남은 이벤트를 보내며 기다리는 최대 시간 (늘리지 않는다) */
    static final Duration SHUTDOWN_FLUSH_WAIT = Duration.ofSeconds(2);
    static final String SHUTDOWN_THREAD_NAME = "aiops-shutdown";
    private static final AtomicBoolean HOOK_REGISTERED = new AtomicBoolean();

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
                registerShutdownHookOnce(); // JVM당 1번. 재시작으로 다시 불려도 늘지 않는다
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
     * 앱이 뜨다가 실패했을 때 그 예외를 STARTUP 이벤트로 바로 보낸다 (AI-45).
     * 로그가 아니라 Spring이 넘겨준 예외를 직접 쓴다 (실패 로그는 이 뒤에 찍힌다).
     * 최대 3초만 기다리고, 실패하면 포기한다. 여기서 난 오류는 조용히 버린다 —
     * 원래 시작 실패를 가리면 안 되므로 비활성 로그도 찍지 않는다.
     */
    public static void reportStartupFailure(Throwable failure) {
        try {
            AiopsRuntime r = instance;
            if (r == null || DISABLED.get() || failure == null) {
                return; // 키 없음·꺼짐·시작 전
            }
            ErrorEvent event = r.mapper.mapThrowable(failure, Phase.STARTUP);
            if (event == null) {
                return;
            }
            ErrorEvent masked = r.masker.mask(event); // DB 주소에 비밀번호가 들어 있을 수 있다
            if (masked == null) {
                return;
            }
            r.buffer.offer(masked);
            r.sender.flushNow(STARTUP_FAILURE_WAIT);
        } catch (Throwable ignored) {
            // Exception이 아니라 Throwable (D-087). 조용히 버린다
        }
    }

    /**
     * JVM 종료 훅을 한 번만 등록한다 (AI-46). 등록했으면 true.
     * Spring의 @PreDestroy가 아니라 JVM 훅인 이유: Starter는 Bean이 아니고, 컨텍스트가 뜨지 못한 경우에도 돌아야
     * 한다.
     * 훅은 한 번 등록하면 뺄 필요가 없다 — 실행될 때 그 시점의 instance를 본다.
     */
    static boolean registerShutdownHookOnce() {
        if (!HOOK_REGISTERED.compareAndSet(false, true)) {
            return false;
        }
        try {
            Thread hook = new Thread(() -> onJvmShutdown(), SHUTDOWN_THREAD_NAME); // 데몬 아님: JVM이 기다려 준다
            Runtime.getRuntime().addShutdownHook(hook);
            return true;
        } catch (Throwable t) {
            return false; // 이미 종료 중이면 등록할 수 없다. 조용히 넘어간다
        }
    }

    static boolean shutdownHookRegistered() {
        return HOOK_REGISTERED.get();
    }

    /**
     * 앱이 끝날 때(Ctrl+C, kill, 컨테이너 stop) 버퍼에 남은 이벤트를 마지막으로 보낸다. 최대 2초.
     * 강제 종료(kill -9)에서는 실행되지 않는다 — 그건 포기한다.
     * 훅 안의 예외는 다른 훅을 방해할 수 있어서 아무것도 밖으로 내보내지 않는다.
     */
    static void onJvmShutdown() {
        try {
            AiopsRuntime r = instance;
            if (r == null) {
                return; // 시작 전이거나 꺼짐
            }
            r.sender.flushNow(SHUTDOWN_FLUSH_WAIT);
            r.sender.stop();
        } catch (Throwable ignored) {
            // Exception이 아니라 Throwable (D-087)
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