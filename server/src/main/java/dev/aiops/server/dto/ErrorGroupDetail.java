package dev.aiops.server.dto;

import dev.aiops.server.model.entity.Feedback;
import dev.aiops.server.model.entity.GroupStatus;
import java.time.Instant;
import java.util.List;

// AI-96 : 그룹 하나를 열었을 때 필요한 전부. 목록(ErrorGroupSummary)이 주지 않는 무거운 데이터는 여기서만 준다
// aiExplanation 은 7단계가 저장한 문자열 그대로 (세 칸 분리는 콘솔이 표시만 다르게. 서버는 파싱·판단하지 않는다)
// recentEvents = 최근 원본 N건 (최신순), dailyCounts = 최근 N일 (오래된 날 → 오늘 순서, 없는 날은 0)
// retentionDays = 원본 보관 일수 (화면의 "원본 보관 기간" 안내용)
public record ErrorGroupDetail(Long id, String exceptionType, String location, String env, GroupStatus status,
                               long count, Instant firstSeen, Instant lastSeen, String fingerprint,
                               String aiExplanation, Instant aiExplainedAt, Feedback feedback,
                               List<RecentEvent> recentEvents, List<DailyCount> dailyCounts, int retentionDays) {
}