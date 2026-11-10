package dev.aiops.starter.transport;

import dev.aiops.protocol.AppInfo;
import dev.aiops.protocol.ErrorEvent;
import dev.aiops.protocol.EventBatch;
import dev.aiops.protocol.Heartbeat;
import dev.aiops.protocol.HelloRequest;
import dev.aiops.protocol.Phase;
import dev.aiops.starter.config.AiopsSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class BatchingEventSenderTest {

    private final MutableClock clock = new MutableClock();
    private final FakeTransport transport = new FakeTransport();
    private final List<Duration> sleeps = new CopyOnWriteArrayList<>();
    private final BoundedEventBuffer buffer = new BoundedEventBuffer(1000);
    private BatchingEventSender sender;

    private static final AiopsSettings SETTINGS = new AiopsSettings(
            "aiops_l_test-only-key", "local", "http://127.0.0.1:1", true, 1000, Duration.ofSeconds(5), "sample-app");
    private static final AppInfo APP = new AppInfo("sample-app", null, null, "0123456789ab");

    private BatchingEventSender newSender() {
        sender = new BatchingEventSender(buffer, transport, SETTINGS, APP, clock, sleeps::add);
        return sender;
    }

    @AfterEach
    void stopSender() {
        if (sender != null) {
            sender.stop();
        }
    }

    private static ErrorEvent event(int i) {
        return new ErrorEvent(1L, "ERROR", "java.lang.IllegalStateException", "msg-" + i,
                List.of(), List.of(), "main", Phase.RUNTIME);
    }

    private void offer(int count) {
        for (int i = 0; i < count; i++) {
            buffer.offer(event(i));
        }
    }

    // ---- 언제 보내나 ----

    @Test
    void 백개가_모이면_시간과_상관없이_바로_한_묶음() {
        BatchingEventSender s = newSender();
        offer(100);

        s.tick();

        assertEquals(1, transport.batches.size());
        assertEquals(100, transport.batches.get(0).events().size());
    }

    @Test
    void 세개는_5초가_지나야_한_묶음() {
        BatchingEventSender s = newSender();
        offer(3);

        s.tick();
        assertEquals(0, transport.batches.size(), "5초 전에는 보내지 않는다");

        clock.advance(Duration.ofSeconds(5));
        s.tick();
        assertEquals(1, transport.batches.size());
        assertEquals(3, transport.batches.get(0).events().size());
    }

    @Test
    void 보낼것도_버린것도_없으면_보내지_않는다() {
        BatchingEventSender s = newSender();

        clock.advance(Duration.ofSeconds(5));
        s.tick();

        assertEquals(0, transport.sendCalls.get());
    }

    // ---- 실패하면 ----

    @Test
    void 실패하면_1초_뒤_정확히_1번만_다시_보내고_포기한다() {
        BatchingEventSender s = newSender();
        transport.sendResult = false;
        offer(1);

        clock.advance(Duration.ofSeconds(5));
        s.tick();

        assertEquals(2, transport.sendCalls.get());
        assertEquals(List.of(Duration.ofSeconds(1)), sleeps);
    }

    @Test
    void 포기한_개수는_다음_묶음의_droppedCount에_실린다() {
        BatchingEventSender s = newSender();
        transport.sendResult = false;
        offer(4);
        clock.advance(Duration.ofSeconds(5));
        s.tick(); // 4개 포기

        transport.sendResult = true;
        offer(1);
        clock.advance(Duration.ofSeconds(5));
        s.tick();

        EventBatch last = transport.batches.get(transport.batches.size() - 1);
        assertEquals(1, last.events().size());
        assertEquals(4, last.droppedCount());
    }

    @Test
    void 버퍼가_버린_개수도_droppedCount에_실린다() {
        BoundedEventBuffer small = new BoundedEventBuffer(100);
        sender = new BatchingEventSender(small, transport, SETTINGS, APP, clock, sleeps::add);
        for (int i = 0; i < 103; i++) {
            small.offer(event(i)); // 3개 버려짐
        }

        sender.tick();

        assertEquals(3, transport.batches.get(0).droppedCount());
    }

    // ---- hello·하트비트 ----

    @Test
    void hello는_다음_바퀴에_한_번만() {
        BatchingEventSender s = newSender();
        s.submitHello(new HelloRequest("local", APP, "17", "4.1.1", List.of(), List.of()));

        s.tick();
        s.tick();

        assertEquals(1, transport.hellos.get());
    }

    @Test
    void 이벤트가_없어도_60초마다_하트비트() {
        BatchingEventSender s = newSender();

        clock.advance(Duration.ofSeconds(59));
        s.tick();
        assertEquals(0, transport.heartbeats.size(), "59초에는 아직");

        clock.advance(Duration.ofSeconds(1));
        s.tick();
        assertEquals(1, transport.heartbeats.size(), "60초");

        clock.advance(Duration.ofSeconds(60));
        s.tick();
        assertEquals(2, transport.heartbeats.size(), "120초");
        assertEquals("local", transport.heartbeats.get(0).env());
    }

    // ---- 종료 처리 ----

    @Test
    void flushNow는_부른_스레드에서_버퍼를_다_비운다() {
        BatchingEventSender s = newSender();
        offer(250);

        s.flushNow(Duration.ofSeconds(2));

        assertEquals(0, buffer.size());
        assertEquals(List.of(100, 100, 50),
                transport.batches.stream().map(b -> b.events().size()).toList());
    }

    // ---- 진짜 스레드 ----

    @Test
    void 스레드는_aiops_sender_데몬_하나이고_stop하면_끝난다() throws Exception {
        BatchingEventSender s = newSender();
        s.start();
        s.start(); // 두 번 불러도 하나

        List<Thread> threads = senderThreads();
        assertEquals(1, threads.size());
        assertTrue(threads.get(0).isDaemon());

        s.stop();
        threads.get(0).join(1500);
        assertFalse(threads.get(0).isAlive());
    }

    @Test
    void Transport가_Error를_던져도_스레드는_살아서_다음_묶음을_보낸다() {
        BatchingEventSender s = newSender();
        transport.throwError = true;
        s.start();

        offer(100);
        assertTrue(waitUntil(() -> transport.sendCalls.get() >= 1), "Error를 던지는 send가 불렸다");

        transport.throwError = false;
        offer(100);
        assertTrue(waitUntil(() -> transport.batches.size() >= 1), "Error 뒤에도 다음 묶음을 보냈다");
        assertEquals(1, senderThreads().size());
    }

    // ---- 도구 ----

    private static List<Thread> senderThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().equals(BatchingEventSender.THREAD_NAME) && t.isAlive())
                .toList();
    }

    private static boolean waitUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    /** 호출을 기록하는 가짜 Transport */
    static final class FakeTransport implements Transport {
        final List<EventBatch> batches = new CopyOnWriteArrayList<>(); // 성공한 묶음만
        final List<Heartbeat> heartbeats = new CopyOnWriteArrayList<>();
        final AtomicInteger sendCalls = new AtomicInteger();
        final AtomicInteger hellos = new AtomicInteger();
        volatile boolean sendResult = true;
        volatile boolean throwError = false;

        @Override
        public boolean send(EventBatch batch) {
            sendCalls.incrementAndGet();
            if (throwError) {
                throw new Error("테스트용 Error");
            }
            if (sendResult) {
                batches.add(batch);
            }
            return sendResult;
        }

        @Override
        public boolean hello(HelloRequest hello) {
            hellos.incrementAndGet();
            return true;
        }

        @Override
        public boolean heartbeat(Heartbeat heartbeat) {
            heartbeats.add(heartbeat);
            return true;
        }
    }

    /** 테스트에서 손으로 움직이는 시계 */
    static final class MutableClock extends Clock {
        private volatile long millis = 1_000_000L;

        void advance(Duration d) {
            millis += d.toMillis();
        }

        @Override
        public long millis() {
            return millis;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}