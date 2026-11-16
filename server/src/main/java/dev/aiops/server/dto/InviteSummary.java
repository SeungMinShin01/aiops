package dev.aiops.server.dto;

import java.time.Instant;

/** 유효한 초대 링크 목록 한 줄 (AI-89). 원문·해시 없음 */
public record InviteSummary(long invitationId, String role, int usedCount, int maxUses, Instant expiresAt,
        Instant createdAt) {
}
