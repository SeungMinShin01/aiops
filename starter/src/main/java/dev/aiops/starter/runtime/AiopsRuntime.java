package dev.aiops.starter.runtime;

import dev.aiops.protocol.AppInfo;
import dev.aiops.starter.config.AiopsSettings;
import dev.aiops.starter.config.StarterVersion;
import dev.aiops.starter.transport.BatchingEventSender;
import dev.aiops.starter.transport.BoundedEventBuffer;
import dev.aiops.starter.transport.EventBuffer;
import dev.aiops.starter.transport.EventSender;
import dev.aiops.starter.transport.HttpTransport;
import dev.aiops.starter.transport.Transport;
import org.springframework.core.env.Environment;

import java.time.Clock;

/**
 * 부품 ②. 설정 → 버퍼 → 전송 → 전송 스레드를 연결한다.
 * JVM당 1번만 만든다 (D-006). DevTools 재시작으로 다시 불려도 같은 것을 돌려준다.
 * 4단계 어펜더는 current().buffer()에 넣고, 5단계 종료 훅·시작 실패는 current().sender()를 쓴다.
 */
public final class AiopsRuntime {

    private static final Object LOCK = new Object();
    private static volatile AiopsRuntime instance; // 다른 스레드(어펜더)도 바로 보이게 volatile

    private final AiopsSettings settings;
    private final EventBuffer buffer;
    private final EventSender sender;

    private AiopsRuntime(AiopsSettings settings, EventBuffer buffer, EventSender sender) {
        this.settings = settings;
        this.buffer = buffer;
        this.sender = sender;
    }

    /** 없으면 만들고 전송 스레드를 시작한다. 꺼야 하면(키 없음·enabled=false·내부 오류) null */
    public static AiopsRuntime startIfAbsent(Environment environment) {
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
                instance = new AiopsRuntime(settings, buffer, sender);
                return instance;
            } catch (Throwable t) {
                System.err.println("[aiops] 비활성화됨: " + t.getClass().getName() + " " + t.getMessage()
                        + " (starter " + StarterVersion.get() + ")");
                return null;
            }
        }
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

    /** 테스트 전용: 스레드를 멈추고 비운다 */
    static void resetForTest() {
        synchronized (LOCK) {
            if (instance != null) {
                instance.sender.stop();
                instance = null;
            }
        }
    }
}