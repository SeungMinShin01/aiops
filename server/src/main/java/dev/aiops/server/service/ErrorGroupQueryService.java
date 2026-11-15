package dev.aiops.server.service;

import dev.aiops.protocol.ErrorCause;
import dev.aiops.protocol.ErrorEvent;
import dev.aiops.protocol.StackFrame;
import dev.aiops.server.dto.DailyCount;
import dev.aiops.server.dto.ErrorGroupDetail;
import dev.aiops.server.dto.ErrorGroupFilter;
import dev.aiops.server.dto.ErrorGroupSummary;
import dev.aiops.server.dto.RecentEvent;
import dev.aiops.server.model.entity.ErrorEventEntity;
import dev.aiops.server.model.entity.ErrorGroupEntity;
import dev.aiops.server.model.repository.ErrorEventRepository;
import dev.aiops.server.model.repository.ErrorGroupQueryRepository;
import dev.aiops.server.model.repository.ErrorGroupRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

// AI-95 : 콘솔 에러 목록 (그룹 단위). 팀은 MembershipGuard 가 정한 TenantContext 로 이미 정해져 있고,
// 이 트랜잭션이 열릴 때 set_config 가 불려서 RLS 가 그 팀 행만 거른다. 여기서 팀 id 를 따로 받지 않는다.
// AI-96 : 그룹 하나의 상세 (최근 원본 N건 + 일별 횟수)
@Service
public class ErrorGroupQueryService {

    private static final Logger log = LoggerFactory.getLogger(ErrorGroupQueryService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");   // 일별 집계의 하루 기준 (SQL 의 'Asia/Seoul' 과 같다)

    // AI-96 : 그룹이 없거나 다른 팀 것일 때. 다른 팀 그룹은 RLS 가 0건으로 만들어서 "없음"과 똑같이 보인다 (404, 존재 여부를 드러내지 않음)
    public static class GroupNotFoundException extends RuntimeException {
        public GroupNotFoundException() {
            super("에러 그룹을 찾을 수 없다");
        }
    }

    private final ErrorGroupQueryRepository queryRepository;
    private final ErrorGroupRepository groupRepository;
    private final ErrorEventRepository eventRepository;
    private final ObjectMapper objectMapper;          // payload JSON 글자 → protocol ErrorEvent
    private final int recentEvents;                   // 최근 원본 몇 건 (설정값, 코드에 숫자를 박지 않는다)
    private final int dailyWindow;                    // 일별 횟수 며칠치
    private final int localRetentionDays;             // local 원본 보관 일수 (RetentionScheduler 와 같은 설정)
    private final int prodRetentionDays;

    public ErrorGroupQueryService(ErrorGroupQueryRepository queryRepository,
                                  ErrorGroupRepository groupRepository,
                                  ErrorEventRepository eventRepository,
                                  ObjectMapper objectMapper,
                                  @Value("${aiops.console.recent-events:5}") int recentEvents,
                                  @Value("${aiops.console.daily-window:14}") int dailyWindow,
                                  @Value("${aiops.retention.local-days:3}") int localRetentionDays,
                                  @Value("${aiops.retention.prod-days:14}") int prodRetentionDays) {
        this.queryRepository = queryRepository;
        this.groupRepository = groupRepository;
        this.eventRepository = eventRepository;
        this.objectMapper = objectMapper;
        this.recentEvents = recentEvents;
        this.dailyWindow = dailyWindow;
        this.localRetentionDays = localRetentionDays;
        this.prodRetentionDays = prodRetentionDays;
    }

    // 조건·정렬·페이지로 한 쪽만 DB 에서 가져와 요약으로 바꾼다 (전부 읽어서 자바에서 거르지 않음)
    @Transactional(readOnly = true)
    public Page<ErrorGroupSummary> list(ErrorGroupFilter filter, Pageable pageable) {
        return queryRepository.search(filter, pageable).map(ErrorGroupQueryService::toSummary);
    }

    // AI-96 : 그룹 상세. 조회 세 번(그룹, 최근 원본, 일별 집계)이 한 트랜잭션이라 같은 팀 설정을 쓴다
    @Transactional(readOnly = true)
    public ErrorGroupDetail detail(Long groupId) {
        // 없거나 다른 팀 그룹이면 RLS 가 0건 → 비어 있음 → 404 (403 이 아니다)
        ErrorGroupEntity group = groupRepository.findById(groupId).orElseThrow(GroupNotFoundException::new);

        int retentionDays = "local".equals(group.getFirstEnv()) ? localRetentionDays : prodRetentionDays;
        return new ErrorGroupDetail(group.getId(), group.getExceptionType(), group.getTopFrame(),
                group.getFirstEnv(), group.getStatus(), group.getOccurrenceCount(),
                group.getFirstSeenAt(), group.getLastSeenAt(), group.getFingerprint(),
                group.getAiExplanation(), group.getAiExplainedAt(), group.getFeedback(),
                recentEventsOf(group.getId()), dailyCountsOf(group.getId()), retentionDays);
    }

    // AI-96 : 최근 원본 N건 (최신순). payload 는 저장된 JSON 그대로 읽고, 값을 새로 만들어 넣지 않는다
    private List<RecentEvent> recentEventsOf(Long groupId) {
        List<ErrorEventEntity> events = eventRepository.findByGroupIdOrderByReceivedAtDesc(
                groupId, PageRequest.of(0, recentEvents));
        List<RecentEvent> result = new ArrayList<>();
        for (ErrorEventEntity event : events) {
            try {
                ErrorEvent payload = objectMapper.readValue(event.getPayload(), ErrorEvent.class);
                result.add(toRecentEvent(payload));
            } catch (RuntimeException e) {
                // 깨진 원본 한 건 때문에 상세 전체가 실패하지 않게 건너뛴다 (내용은 로그에 남기지 않음)
                log.warn("원본 읽기 실패: 건너뜀 groupId={} eventId={} {}", groupId, event.getId(), e.getClass().getSimpleName());
            }
        }
        return result;
    }

    // AI-96 : 최근 N일 일별 횟수. 집계는 DB(GROUP BY), 여기서는 빈 날을 0으로 채워 "오래된 날 → 오늘" 순서의 N칸으로 만든다
    private List<DailyCount> dailyCountsOf(Long groupId) {
        LocalDate today = LocalDate.now(KST);
        LocalDate firstDay = today.minusDays(dailyWindow - 1L);
        Instant since = firstDay.atStartOfDay(KST).toInstant();

        Map<LocalDate, Long> counts = new HashMap<>();
        for (Object[] row : eventRepository.countByDay(groupId, since)) {
            counts.put(LocalDate.parse((String) row[0]), ((Number) row[1]).longValue());
        }

        List<DailyCount> result = new ArrayList<>();
        for (int i = 0; i < dailyWindow; i++) {
            LocalDate day = firstDay.plusDays(i);
            Long count = counts.get(day);
            result.add(new DailyCount(day, count == null ? 0 : count));
        }
        return result;
    }

    private static RecentEvent toRecentEvent(ErrorEvent payload) {
        List<String> stack = new ArrayList<>();
        for (StackFrame frame : payload.stack()) {
            stack.add(formatFrame(frame));
        }
        List<String> causes = new ArrayList<>();
        for (ErrorCause cause : payload.causes()) {
            causes.add(cause.message() == null ? cause.type() : cause.type() + ": " + cause.message());
        }
        String phase = payload.phase() == null ? null : payload.phase().name();
        return new RecentEvent(Instant.ofEpochMilli(payload.time()), payload.message(), stack, causes,
                payload.thread(), phase);
    }

    // 자바 스택트레이스 한 줄과 같은 모양: 클래스.메서드(파일:줄). lineNumber -1 = 줄 모름, -2 = 네이티브 메서드
    private static String formatFrame(StackFrame frame) {
        String where;
        if (frame.lineNumber() == -2) {
            where = "Native Method";
        } else if (frame.fileName() == null) {
            where = "Unknown Source";
        } else if (frame.lineNumber() >= 0) {
            where = frame.fileName() + ":" + frame.lineNumber();
        } else {
            where = frame.fileName();
        }
        return frame.className() + "." + frame.methodName() + "(" + where + ")";
    }

    private static ErrorGroupSummary toSummary(ErrorGroupEntity group) {
        return new ErrorGroupSummary(group.getId(), group.getExceptionType(), group.getTopFrame(),
                group.getFirstEnv(), group.getStatus(), group.getOccurrenceCount(),
                group.getFirstSeenAt(), group.getLastSeenAt(), group.getAiExplanation() != null);
    }
}