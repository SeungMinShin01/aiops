package dev.aiops.server.dto;

import dev.aiops.server.model.entity.GroupStatus;
import java.time.Instant;

// AI-95 : 에러 목록 한 줄. 스택·payload·AI 설명 본문은 넣지 않는다 (응답 크기). 그건 상세(AI-96)에서만
// location = 저장된 팀 코드 첫 프레임(top_frame), hasExplanation = ai_explanation 이 비어 있지 않은가
public record ErrorGroupSummary(Long id, String exceptionType, String location, String env, GroupStatus status,
                                long count, Instant firstSeen, Instant lastSeen, boolean hasExplanation) {
}