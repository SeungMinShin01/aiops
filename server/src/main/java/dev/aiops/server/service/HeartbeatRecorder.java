package dev.aiops.server.service;

import dev.aiops.server.model.repository.HeartbeatLastRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;

// "이 앱이 방금 살아 있었다"를 heartbeat_last 에 기록하는 담당자.
// 에러 묶음(AI-31)과 hello·하트비트(AI-33)가 같이 씀
@Service
public class HeartbeatRecorder {

    private final HeartbeatLastRepository heartbeatLastRepository;

    public HeartbeatRecorder(HeartbeatLastRepository heartbeatLastRepository) {
        this.heartbeatLastRepository = heartbeatLastRepository;
    }

    // AI-33 : 신호 하나를 기록함. started 는 hello 일 때만 시각을 넘기고, 나머지는 null (기존 값 유지)
    // 호출하는 쪽의 트랜잭션 안에서 실행됨
    public void touch(Long tenantId, String env, String hostHash, String appName, Instant started) {
        // host_hash 컬럼이 NOT NULL 이라서 값이 없으면 기록하지 않고 넘어감
        if (hostHash == null || hostHash.isBlank()) {
            return;
        }
        heartbeatLastRepository.touch(tenantId, env, hostHash, appName, started);
    }
}