package dev.aiops.server.service;

import dev.aiops.protocol.AppInfo;
import dev.aiops.protocol.Heartbeat;
import dev.aiops.protocol.HelloRequest;
import dev.aiops.server.model.entity.AppSnapshotEntity;
import dev.aiops.server.model.repository.AppSnapshotRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

// hello·heartbeat 를 처리하는 곳. 시각 기록은 HeartbeatRecorder 를 호출만 함
@Service
public class HelloService {

    private final AppSnapshotRepository appSnapshotRepository;
    private final HeartbeatRecorder heartbeatRecorder;
    private final ObjectMapper objectMapper;   // 객체 → JSON 글자 변환기

    public HelloService(AppSnapshotRepository appSnapshotRepository,
                        HeartbeatRecorder heartbeatRecorder,
                        ObjectMapper objectMapper) {
        this.appSnapshotRepository = appSnapshotRepository;
        this.heartbeatRecorder = heartbeatRecorder;
        this.objectMapper = objectMapper;
    }

    // AI-33 : hello 환경 정보 원본을 app_snapshot 에 1행 쌓고, 마지막 hello 시각(started_at)을 기록함
    @Transactional
    public void storeHello(Long tenantId, String env, HelloRequest hello) {
        AppInfo app = hello.app();
        String appName = app == null ? null : app.name();
        String hostHash = app == null ? null : app.hostHash();
        Instant now = Instant.now();

        // payload 는 받은 HelloRequest 를 JSON 글자로 바꾼 것 (이벤트와 같은 방식)
        appSnapshotRepository.save(new AppSnapshotEntity(
                tenantId, env, hostHash, objectMapper.writeValueAsString(hello), now));
        heartbeatRecorder.touch(tenantId, env, hostHash, appName, now);
    }

    // AI-33 : heartbeat 는 last_seen_at 만 갱신함 (started 는 null → 기존 started_at 유지)
    @Transactional
    public void recordHeartbeat(Long tenantId, String env, Heartbeat heartbeat) {
        AppInfo app = heartbeat.app();
        String appName = app == null ? null : app.name();
        String hostHash = app == null ? null : app.hostHash();
        heartbeatRecorder.touch(tenantId, env, hostHash, appName, null);
    }
}