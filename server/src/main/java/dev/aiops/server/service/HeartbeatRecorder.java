package dev.aiops.server.service;

import dev.aiops.server.model.repository.HeartbeatLastRepository;
import org.springframework.stereotype.Service;

// "이 앱이 방금 살아 있었다"를 heartbeat_last 에 기록하는 담당자.
// 에러 묶음(AI-31)과 hello·하트비트(AI-33)가 같이 씀
@Service
public class HeartbeatRecorder {

    private final HeartbeatLastRepository heartbeatLastRepository;

    public HeartbeatRecorder(HeartbeatLastRepository heartbeatLastRepository) {
        this.heartbeatLastRepository = heartbeatLastRepository;
    }

    // 호출하는 쪽의 트랜잭션 안에서 실행됨
    public void recordSeen(Long tenantId, String env, String hostHash, String appName) {
        // host_hash 컬럼이 NOT NULL 이라서 값이 없으면 기록하지 않고 넘어감
        if (hostHash == null || hostHash.isBlank()) {
            return;
        }
        heartbeatLastRepository.upsertLastSeen(tenantId, env, hostHash, appName);
    }

    // AI-33 추가: hello 를 받았을 때. 마지막 hello 시각(started_at)까지 기록. hostHash 없으면 기록 안 함(위와 같은 이유)
    public void recordHello(Long tenantId, String env, String hostHash, String appName) {
        if (hostHash == null || hostHash.isBlank()) {
            return;
        }
        heartbeatLastRepository.upsertHello(tenantId, env, hostHash, appName);
    }
}