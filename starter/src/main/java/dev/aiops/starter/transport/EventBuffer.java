package dev.aiops.starter.transport;

import dev.aiops.protocol.ErrorEvent;

import java.util.List;

/**
 * 팀 앱 스레드와 Starter 전송 스레드 사이의 경계 (부품 ⑥).
 * offer는 팀 앱 스레드에서 불린다 → 절대 기다리지 않는다 (규칙 5). 꽉 차면 버리고 개수만 센다.
 * 구현은 BoundedEventBuffer (AI-36). 메서드 이름·파라미터는 바꾸지 않는다 (규칙 2).
 */
public interface EventBuffer {

    /** 넣기. 꽉 찼거나 null이면 false (버린 개수 +1). 대기하지 않고, 예외를 던지지 않는다 */
    boolean offer(ErrorEvent event);

    /** 최대 max개를 꺼낸다. 전송 스레드가 부른다. 비어 있으면 빈 리스트 */
    List<ErrorEvent> drain(int max);

    /** 지금까지 버린 개수를 읽고 0으로 되돌린다. EventBatch.droppedCount에 실린다 */
    int takeDroppedCount();

    /** 현재 들어 있는 개수 */
    int size();
}