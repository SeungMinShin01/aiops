package dev.aiops.server.service;

import dev.aiops.protocol.AppInfo;
import dev.aiops.protocol.ErrorEvent;
import dev.aiops.protocol.EventBatch;
import dev.aiops.protocol.ProtocolConstants;
import dev.aiops.server.model.entity.ErrorEventEntity;
import dev.aiops.server.model.repository.ErrorEventRepository;
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
    private final IngestStatRecorder ingestStatRecorder;   // AI-62 : 통계 기록은 이 클래스가 담당
    private final HeartbeatRecorder heartbeatRecorder;
    private final ApplicationEventPublisher publisher;   // 서버 내부 방송 도구
    private final ObjectMapper objectMapper;             // 객체 → JSON 글자 변환기

    public IngestService(ErrorEventRepository errorEventRepository,
                         IngestStatRecorder ingestStatRecorder,
                         HeartbeatRecorder heartbeatRecorder,
                         ApplicationEventPublisher publisher,
                         ObjectMapper objectMapper) {
        this.errorEventRepository = errorEventRepository;
        this.ingestStatRecorder = ingestStatRecorder;
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

        // ② 오늘 받은 수·Starter 가 버린 수를 더함 (AI-62 : IngestStatRecorder 가 같은 트랜잭션 안에서 기록, 날짜는 서울 기준)
        ingestStatRecorder.record(tenantId, env, batch.events().size(), batch.droppedCount());

        // ③ 이 앱이 살아 있다고 기록 (에러 묶음도 하트비트로 침, D-086)
        // AI-33 : 이벤트 경로는 시작 시각을 모르니 started 에 null (기존 started_at 유지)
        heartbeatRecorder.touch(tenantId, env, hostHash, appName, null);

        // ④ 저장된 id 가 있을 때만 "저장됐다" 알림을 발행. 커밋 뒤 처리는 AI-32 리스너가 함
        if (!eventIds.isEmpty()) {
            publisher.publishEvent(new ErrorsStored(tenantId, eventIds));
        }
    }

    // 이벤트 1건을 JSON 글자로 바꿈
    private String toJson(ErrorEvent event) {
        return objectMapper.writeValueAsString(event);
    }
}