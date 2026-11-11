package dev.aiops.server.dto;

import java.time.Instant;

// AI-87 : 접근 기록 조회 응답 한 줄. 본문·토큰 같은 건 원래 저장하지 않아서 여기에도 없음
public record AccessLogRow(long id, long adminUserId, Long tenantId, String action, Instant at) {
}