package dev.aiops.server.dto;

import java.time.LocalDate;

// AI-82 : 기수 응답 (생성·목록 공통). teamCount = 그 기수에 속한 팀 수 (방금 만든 기수는 0)
public record CohortResponse(Long cohortId, String name, LocalDate startDate, LocalDate endDate, long teamCount) {
}