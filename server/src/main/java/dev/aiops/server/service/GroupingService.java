package dev.aiops.server.service;

import dev.aiops.protocol.ErrorEvent;
import dev.aiops.protocol.StackFrame;
import dev.aiops.server.model.entity.ErrorEventEntity;
import dev.aiops.server.model.entity.GroupStatus;
import dev.aiops.server.model.repository.ErrorEventRepository;
import dev.aiops.server.model.repository.ErrorGroupRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

// AI-52 : 이벤트 1건을 그룹에 넣는 곳. 이벤트마다 짧은 트랜잭션 (ErrorProcessor 가 이벤트마다 호출)
@Service
public class GroupingService {

    private final ErrorEventRepository errorEventRepository;
    private final GroupUpserter groupUpserter;
    private final ErrorGroupRepository groupRepository;               // AI-97 : 재발 시 RESOLVED → OPEN
    private final ObjectMapper objectMapper;                          // JSON 글자 → 객체 변환기
    private final Fingerprinter fingerprinter = new Fingerprinter();  // 순수 계산기라 그냥 만들어 씀

    public GroupingService(ErrorEventRepository errorEventRepository,
                           GroupUpserter groupUpserter,
                           ErrorGroupRepository groupRepository,
                           ObjectMapper objectMapper) {
        this.errorEventRepository = errorEventRepository;
        this.groupUpserter = groupUpserter;
        this.groupRepository = groupRepository;
        this.objectMapper = objectMapper;
    }

    // AI-97 : 알려야 하는 결과. reopened = 해결(RESOLVED)로 표시했던 그룹이 다시 발생해서 OPEN 으로 돌아왔다
    public record Outcome(Long groupId, boolean reopened) {
    }

    // AI-52 : 새 그룹이 만들어졌으면 그 그룹 id, 아니면 null. 이미 처리됐거나 없는 이벤트도 null
    // AI-97 : 새 그룹이거나 다시 열린 그룹이면 Outcome, 그 밖에는 null (알릴 일 없음)
    // 예외가 나면 이 이벤트의 변경은 전부 취소되고(processed=false 유지) 호출한 쪽이 로그만 남김
    @Transactional
    public Outcome groupOne(Long tenantId, Long eventId) {
        ErrorEventEntity event = errorEventRepository.findById(eventId).orElse(null);
        // 없거나, 다른 팀 것이거나, 이미 처리됐으면 건너뜀 (중복 호출해도 안전)
        if (event == null || !event.getTenantId().equals(tenantId) || event.isProcessed()) {
            return null;
        }

        // payload JSON → protocol ErrorEvent (깨진 payload 면 여기서 예외)
        ErrorEvent payload = objectMapper.readValue(event.getPayload(), ErrorEvent.class);

        // 지문에 쓸 예외 타입 = 원인 체인의 맨 안쪽 (causes 는 바깥 → 안쪽 순서)
        String type = payload.exceptionType();
        if (!payload.causes().isEmpty()) {
            String inner = payload.causes().get(payload.causes().size() - 1).type();
            if (inner != null) {
                type = inner;
            }
        }
        if (type == null) {
            type = "unknown";   // exception_type 컬럼이 NOT NULL
        }
        if (type.length() > 300) {
            type = type.substring(0, 300);   // 컬럼 길이(300)에 맞춤
        }

        String fingerprint = fingerprinter.fingerprint(type, payload.stack());
        GroupUpserter.GroupSummary summary = new GroupUpserter.GroupSummary(
                type, topFrameOf(payload.stack()), Instant.ofEpochMilli(payload.time()));

        GroupUpserter.UpsertResult result = groupUpserter.upsert(
                tenantId, event.getEnv(), fingerprint, summary);

        // 이벤트에 그룹을 달고 processed=true (트랜잭션이 끝날 때 UPDATE 됨)
        event.assignGroup(result.groupId());

        if (result.isNew()) {
            return new Outcome(result.groupId(), false);
        }
        // AI-97 : 기존 그룹이 다시 발생. RESOLVED 였다면 OPEN 으로 (한 번의 UPDATE 로 확인과 변경을 같이 해서, 동시에 와도 한 건만 "다시 열림")
        boolean reopened = groupRepository.reopenIfResolved(
                result.groupId(), GroupStatus.OPEN, GroupStatus.RESOLVED) > 0;
        return reopened ? new Outcome(result.groupId(), true) : null;
    }

    // 위치 표시용: 팀 코드 첫 프레임 "클래스#메서드". 팀 프레임이 없으면 전체 첫 프레임
    private String topFrameOf(List<StackFrame> frames) {
        StackFrame chosen = null;
        for (StackFrame frame : frames) {
            if (!isFramework(frame.className())) {
                chosen = frame;
                break;
            }
        }
        if (chosen == null && !frames.isEmpty()) {
            chosen = frames.get(0);
        }
        if (chosen == null) {
            return null;
        }
        String text = chosen.className() + "#" + chosen.methodName();
        return text.length() > 500 ? text.substring(0, 500) : text;   // top_frame 컬럼 길이(500)
    }

    // 프레임워크 접두사 목록은 Fingerprinter 와 같은 것을 씀
    private boolean isFramework(String className) {
        if (className == null) {
            return false;
        }
        for (String prefix : Fingerprinter.FRAMEWORK_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}