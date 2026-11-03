package dev.aiops.server.service;

import dev.aiops.protocol.AppInfo;
import dev.aiops.protocol.ErrorEvent;
import dev.aiops.protocol.EventBatch;
import dev.aiops.protocol.Heartbeat;      
import dev.aiops.protocol.HelloRequest;   
import dev.aiops.protocol.ProtocolConstants;
import dev.aiops.server.model.entity.AppSnapshotEntity;   
import dev.aiops.server.model.entity.ErrorEventEntity;
import dev.aiops.server.model.repository.AppSnapshotRepository;   
import dev.aiops.server.model.repository.ErrorEventRepository;
import dev.aiops.server.model.repository.IngestStatRepository;
import java.time.Instant;   
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

// 수집 요청을 실제로 처리하는 곳
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final ErrorEventRepository errorEventRepository;
    private final IngestStatRepository ingestStatRepository;
    private final AppSnapshotRepository appSnapshotRepository;   // AI-33 추가
    private final HeartbeatRecorder heartbeatRecorder;
    private final ApplicationEventPublisher publisher;   // 서버 내부 방송 도구
    private final ObjectMapper objectMapper;             // 객체 → JSON 글자 변환기

    public IngestService(ErrorEventRepository errorEventRepository,
                         IngestStatRepository ingestStatRepository,
                         AppSnapshotRepository appSnapshotRepository,   // AI-33 추가
                         HeartbeatRecorder heartbeatRecorder,
                         ApplicationEventPublisher publisher,
                         ObjectMapper objectMapper) {
        this.errorEventRepository = errorEventRepository;
        this.ingestStatRepository = ingestStatRepository;
        this.appSnapshotRepository = appSnapshotRepository;   // AI-33 추가
        this.heartbeatRecorder = heartbeatRecorder;
        this.publisher = publisher;
        this.objectMapper = objectMapper;
    }

    // 묶음이 규칙에 맞는지 검사. 어기면 예외
    public void validate(EventBatch batch) {
        // 100개 초과면 거절. 빈 묶음(0개)은 정상 (버린 개수만 알리는 경우)
        if (batch.events().size() > ProtocolConstants.MAX_BATCH_SIZE) {
            throw new TooManyEventsException("too many events");
        }
    }

    // 저장. 아래 4가지가 한 트랜잭션: 전부 성공하거나 하나라도 실패하면 전부 취소
    @Transactional
    public void store(Long tenantId, String env, EventBatch batch) {
        // env 는 키의 env 를 씀. 본문의 env 와 다르면 경고만 남김
        if (batch.env() != null && !batch.env().equals(env)) {
            log.warn("env mismatch key={} body={}", env, batch.env());
        }

        // app 정보가 없을 수도 있어서 null 방어
        AppInfo app = batch.app();
        String appName = app == null ? null : app.name();
        String appCommit = app == null ? null : app.commit();
        String hostHash = app == null ? null : app.hostHash();

        // ① 이벤트마다 1행. payload 는 이벤트를 JSON 글자로 바꾼 것 (필드를 컬럼으로 풀지 않음)
        List<ErrorEventEntity> rows = new ArrayList<>();
        for (ErrorEvent event : batch.events()) {
            rows.add(new ErrorEventEntity(tenantId, env, toJson(event), appName, appCommit, hostHash));
        }
        // 하나씩 save() 하지 않고 saveAll 로 한 번에
        List<Long> eventIds = errorEventRepository.saveAll(rows).stream()
                .map(ErrorEventEntity::getId)
                .toList();

        // ② 오늘 수집·버린 개수를 더함 (UTC 날짜 기준)
        ingestStatRepository.upsertCounts(tenantId, env, LocalDate.now(ZoneOffset.UTC),
                eventIds.size(), batch.droppedCount());

        // ③ 이 앱이 살아 있다고 기록 (에러 묶음도 하트비트로 침, D-086)
        heartbeatRecorder.recordSeen(tenantId, env, hostHash, appName);

        // ④ 저장된 id 가 있을 때만 "저장됐다" 알림을 발행. 커밋 뒤 처리는 AI-32 리스너가 함
        if (!eventIds.isEmpty()) {
            publisher.publishEvent(new ErrorsStored(tenantId, eventIds));
        }
    }

    // AI-33 추가: hello. 환경 정보 원본을 app_snapshot 에 1행 쌓고, 마지막 hello 시각을 기록 (한 트랜잭션)
    @Transactional
    public void storeHello(Long tenantId, String env, HelloRequest hello) {
        AppInfo app = hello.app();
        String appName = app == null ? null : app.name();
        String hostHash = app == null ? null : app.hostHash();

        // payload 는 받은 HelloRequest 를 JSON 글자로 바꾼 것 (이벤트와 같은 방식)
        appSnapshotRepository.save(new AppSnapshotEntity(
                tenantId, env, hostHash, objectMapper.writeValueAsString(hello), Instant.now()));
        heartbeatRecorder.recordHello(tenantId, env, hostHash, appName);
    }

    // AI-33 추가: heartbeat. 저장할 것 없이 "방금 살아 있었다"만 기록
    @Transactional
    public void recordHeartbeat(Long tenantId, String env, Heartbeat heartbeat) {
        AppInfo app = heartbeat.app();
        String appName = app == null ? null : app.name();
        String hostHash = app == null ? null : app.hostHash();
        heartbeatRecorder.recordSeen(tenantId, env, hostHash, appName);
    }

    // 이벤트 1건을 JSON 글자로 바꿈
    private String toJson(ErrorEvent event) {
        return objectMapper.writeValueAsString(event);
    }
}