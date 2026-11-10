package dev.aiops.starter.transport;

import dev.aiops.protocol.AppInfo;
import dev.aiops.protocol.ErrorEvent;
import dev.aiops.protocol.EventBatch;
import dev.aiops.protocol.Heartbeat;
import dev.aiops.protocol.HelloRequest;
import dev.aiops.protocol.ProtocolConstants;
import dev.aiops.starter.config.AiopsSettings;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Starter 전용 데몬 스레드(aiops-sender)가 버퍼를 비워 묶음으로 보낸다 (부품 ⑦).
 * - 100개가 모이거나 flushInterval(기본 5초)이 지나면 전송. 실패하면 1초 뒤 1회만 재시도, 또 실패하면 버림
 * - 버린 개수는 다음 묶음의 droppedCount에 더해 서버에 알린다
 * - 60초마다 하트비트 (D-086). 별도 타이머·스레드 없이 이 스레드에서만
 * - 루프 한 바퀴를 catch (Throwable)로 감싼다. 어떤 예외·Error도 스레드를 죽이지 않는다 (D-087)
 */
public final class BatchingEventSender implements EventSender {

    static final String THREAD_NAME = "aiops-sender";
    static final long LOOP_SLEEP_MS = 200; // 바쁜 대기 대신 0.2초씩 쉼
    static final Duration RETRY_DELAY = Duration.ofSeconds(1);
    private static final long HEARTBEAT_MS = ProtocolConstants.HEARTBEAT_INTERVAL_SECONDS * 1000L;

    /** 재시도 전 대기. 테스트에서 실제로 잠들지 않게 바꿔 끼운다 */
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final EventBuffer buffer;
    private final Transport transport;
    private final AppInfo appInfo;
    private final String env;
    private final long flushIntervalMs;
    private final Clock clock;
    private final Sleeper sleeper;

    private final AtomicReference<HelloRequest> pendingHello = new AtomicReference<>();
    private final AtomicInteger carriedDropped = new AtomicInteger(); // 보내지 못하고 버린 개수 (다음 묶음에 실음)
    private final AtomicBoolean errorLogged = new AtomicBoolean();
    private final Object sendLock = new Object(); // 전송 스레드와 flushNow가 동시에 보내지 않게

    private volatile boolean running;
    private Thread thread;

    // tick()에서만 읽고 쓴다 (전송 스레드 하나, 테스트에서는 테스트 스레드 하나)
    private long lastFlushAt;
    private long lastHeartbeatAt;

    public BatchingEventSender(EventBuffer buffer, Transport transport, AiopsSettings settings,
            AppInfo appInfo, Clock clock) {
        // 실제 실행에서는 진짜로 잠든다
        this(buffer, transport, settings, appInfo, clock, d -> Thread.sleep(d.toMillis()));
    }

    BatchingEventSender(EventBuffer buffer, Transport transport, AiopsSettings settings,
            AppInfo appInfo, Clock clock, Sleeper sleeper) {
        this.buffer = buffer;
        this.transport = transport;
        this.appInfo = appInfo;
        this.env = settings.env();
        this.flushIntervalMs = settings.flushInterval().toMillis();
        this.clock = clock;
        this.sleeper = sleeper;
        long now = clock.millis();
        this.lastFlushAt = now;
        this.lastHeartbeatAt = now;
    }

    @Override
    public synchronized void start() {
        try {
            if (thread != null) {
                return; // 이미 시작했으면 아무것도 안 함
            }
            running = true;
            thread = new Thread(() -> runLoop(), THREAD_NAME); // 새 스레드가 runLoop()를 실행한다
            thread.setDaemon(true); // 팀 앱이 끝날 때 JVM을 붙잡지 않는다
            thread.start();
        } catch (Throwable t) {
            logOnce(t);
        }
    }

    @Override
    public void submitHello(HelloRequest hello) {
        if (hello != null) {
            pendingHello.set(hello); // 다음 바퀴에 전송 스레드가 1회 보낸다
        }
    }

    /**
     * 부른 스레드(종료 훅·시작 실패)에서 직접 비운다. 전송 스레드를 기다리지 않는다.
     * 재시도는 하지 않는다 (시간 제한이 있으므로). maxWait는 묶음 사이에서 확인한다.
     */
    @Override
    public void flushNow(Duration maxWait) {
        try {
            long deadline = System.nanoTime() + maxWait.toNanos();
            do {
                sendBatch(false);
            } while (buffer.size() > 0 && System.nanoTime() < deadline);
        } catch (Throwable t) {
            logOnce(t);
        }
    }

    @Override
    public void stop() {
        try {
            running = false;
            Thread t;
            synchronized (this) {
                t = thread;
            }
            if (t == null) {
                return;
            }
            t.interrupt();
            t.join(1000); // 최대 1초
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            logOnce(t);
        }
    }

    private void runLoop() {
        while (running) {
            try {
                tick();
            } catch (Throwable t) { // Error까지 잡는다. 스레드는 계속 산다
                logOnce(t);
            }
            try {
                Thread.sleep(LOOP_SLEEP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return; // stop()이 깨운 것
            }
        }
    }

    /** 루프 한 바퀴. hello → 이벤트 → 하트비트 순서. 테스트에서는 가짜 시계와 함께 직접 부른다 */
    void tick() {
        long now = clock.millis();

        HelloRequest hello = pendingHello.getAndSet(null);
        if (hello != null) {
            transport.hello(hello); // 실패하면 포기 (재시도 없음)
        }

        if (buffer.size() >= ProtocolConstants.MAX_BATCH_SIZE || now - lastFlushAt >= flushIntervalMs) {
            sendBatch(true);
            lastFlushAt = now;
        }

        if (now - lastHeartbeatAt >= HEARTBEAT_MS) {
            transport.heartbeat(new Heartbeat(env, appInfo, now)); // 실패해도 다음 60초를 기다린다
            lastHeartbeatAt = now;
        }
    }

    /** 버퍼에서 최대 100개를 꺼내 한 묶음으로 보낸다. 실패하면 retry일 때만 1초 뒤 1회 더 */
    private void sendBatch(boolean retry) {
        synchronized (sendLock) {
            List<ErrorEvent> events = buffer.drain(ProtocolConstants.MAX_BATCH_SIZE);
            int dropped = buffer.takeDroppedCount() + carriedDropped.getAndSet(0);
            if (events.isEmpty() && dropped == 0) {
                return; // 보낼 것도, 알릴 것도 없음
            }
            EventBatch batch = new EventBatch(env, appInfo, events, dropped);
            if (transport.send(batch)) {
                return;
            }
            if (retry) {
                try {
                    sleeper.sleep(RETRY_DELAY);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); // 종료 중이면 재시도하지 않는다
                    carriedDropped.addAndGet(events.size() + dropped);
                    return;
                }
                if (transport.send(batch)) {
                    return;
                }
            }
            // 포기: 이 묶음의 이벤트 수와, 서버에 전달되지 못한 버린 개수를 다음 묶음에 싣는다
            carriedDropped.addAndGet(events.size() + dropped);
        }
    }

    private void logOnce(Throwable t) {
        if (errorLogged.compareAndSet(false, true)) {
            System.err.println("[aiops] 전송 스레드 오류: " + t.getClass().getName()
                    + " (이후 같은 오류는 기록하지 않음)");
        }
    }
}