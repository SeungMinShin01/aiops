package main.java.dev.aiops.protocol;

import java.util.List;

/**
 * POST /v1/events 본문.
 * env: "local" 또는 "prod"
 * events: 최대 ProtocolConstants.MAX_BATCH_SIZE
 * droppedCount: 이 묶음을 보내기 전까지 버퍼가 꽉 차서 버린 개수 (Starter가 센다)
 */
public record EventBatch(
        String env,
        AppInfo app,
        List<ErrorEvent> events,
        int droppedCount) {
    public EventBatch {
        events = events == null ? List.of() : List.copyOf(events);
    }
}