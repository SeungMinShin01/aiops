package dev.aiops.starter.transport;

import dev.aiops.protocol.ErrorEvent;
import dev.aiops.starter.config.Defaults;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 크기가 정해진 버퍼 (부품 ⑥).
 * 팀 앱 스레드가 offer, 전송 스레드가 drain 한다.
 * 꽉 차면 기다리지 않고 버린 뒤 개수만 센다 (규칙 5: 팀 앱 스레드에서 대기 금지).
 */
public final class BoundedEventBuffer implements EventBuffer {

    private final ArrayBlockingQueue<ErrorEvent> queue;
    private final AtomicInteger dropped = new AtomicInteger();

    /**
     * capacity는 AiopsSettings에서 이미 범위가 검사된 값이 들어온다.
     * 그래도 생성자에서 예외를 던지지 않도록(규칙 4) 1 미만이면 기본값을 쓴다.
     */
    public BoundedEventBuffer(int capacity) {
        int safe = capacity < 1 ? Defaults.BUFFER_CAPACITY : capacity;
        this.queue = new ArrayBlockingQueue<>(safe);
    }

    @Override
    public boolean offer(ErrorEvent event) {
        // null이거나 꽉 찼으면 버린다. queue.offer는 기다리지 않는다 (put과 다름)
        if (event == null || !queue.offer(event)) {
            dropped.incrementAndGet();
            return false;
        }
        return true;
    }

    @Override
    public List<ErrorEvent> drain(int max) {
        if (max <= 0) {
            return List.of();
        }
        List<ErrorEvent> out = new ArrayList<>(Math.min(max, queue.size()));
        queue.drainTo(out, max);
        return out;
    }

    @Override
    public int takeDroppedCount() {
        return dropped.getAndSet(0);
    }

    @Override
    public int size() {
        return queue.size();
    }
}