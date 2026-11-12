package dev.aiops.server.dto;

import java.time.Instant;

/** 팀 키 목록 한 줄 (AI-88, 조장·팀원 조회용). 원문·해시 없음, prefix는 복원 불가능한 앞자리 */
public record ApiKeySummary(long keyId, String env, String prefix, Instant createdAt, Instant revokedAt,
        Instant lastUsedAt) {
}
