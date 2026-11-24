package dev.aiops.starter.transport;

import dev.aiops.protocol.ErrorEvent;
import dev.aiops.protocol.Phase;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BoundedEventBufferTest {

    private static ErrorEvent event(int i) {
        return new ErrorEvent(
                System.currentTimeMillis(), "ERROR", "java.lang.IllegalStateException",
                "msg-" + i, List.of(), List.of(), "main", Phase.RUNTIME);
    }

    @Test
    void 용량_1000에서_1001번째는_버리고_개수를_센다() {
        BoundedEventBuffer buffer = new BoundedEventBuffer(1000);
        for (int i = 0; i < 1000; i++) {
            assertTrue(buffer.offer(event(i)));
        }

        assertFalse(buffer.offer(event(1000)));
        assertEquals(1000, buffer.size());
        assertEquals(1, buffer.takeDroppedCount());
    }

    @Test
    void drain은_넣은_순서대로_max개까지만_꺼낸다() {
        BoundedEventBuffer buffer = new BoundedEventBuffer(100);
        for (int i = 0; i < 5; i++) {
            buffer.offer(event(i));
        }

        List<ErrorEvent> first = buffer.drain(3);
        assertEquals(List.of("msg-0", "msg-1", "msg-2"),
                first.stream().map(ErrorEvent::message).toList());
        assertEquals(2, buffer.size());

        List<ErrorEvent> rest = buffer.drain(10);
        assertEquals(List.of("msg-3", "msg-4"),
                rest.stream().map(ErrorEvent::message).toList());
        assertEquals(0, buffer.size());
    }

    @Test
    void 비어_있으면_빈_리스트() {
        BoundedEventBuffer buffer = new BoundedEventBuffer(100);

        assertTrue(buffer.drain(10).isEmpty());
        assertTrue(buffer.drain(0).isEmpty());
    }

    @Test
    void takeDroppedCount는_읽고_0으로_되돌린다() {
        BoundedEventBuffer buffer = new BoundedEventBuffer(100);
        for (int i = 0; i < 103; i++) {
            buffer.offer(event(i));
        }

        assertEquals(3, buffer.takeDroppedCount());
        assertEquals(0, buffer.takeDroppedCount());
    }

    @Test
    void null은_false이고_버린_개수에_센다() {
        BoundedEventBuffer buffer = new BoundedEventBuffer(100);

        assertFalse(assertDoesNotThrow(() -> buffer.offer(null)));
        assertEquals(0, buffer.size());
        assertEquals(1, buffer.takeDroppedCount());
    }

    @Test
    void 스레드_8개가_동시에_넣어도_넣은것과_버린것의_합이_맞다() throws Exception {
        int threads = 8;
        int perThread = 10_000;
        BoundedEventBuffer buffer = new BoundedEventBuffer(1000);
        AtomicInteger accepted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    if (buffer.offer(event(i))) {
                        accepted.incrementAndGet();
                    }
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        int dropped = buffer.takeDroppedCount();
        assertEquals(1000, accepted.get());
        assertEquals(1000, buffer.size());
        assertEquals(threads * perThread, accepted.get() + dropped);
    }

    @Test
    void 꽉_찬_상태에서_100만번_넣어도_1초_안에_끝난다() {
        BoundedEventBuffer buffer = new BoundedEventBuffer(1000);
        ErrorEvent e = event(0);

        long startNs = System.nanoTime();
        for (int i = 0; i < 1_000_000; i++) {
            buffer.offer(e);
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);

        assertTrue(elapsedMs < 1000, "기다리지 않아야 한다. 걸린 시간: " + elapsedMs + "ms");
        assertEquals(1_000_000 - 1000, buffer.takeDroppedCount());
    }
}