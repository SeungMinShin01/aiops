package dev.aiops.server.dto;

import java.time.Instant;

/** 팀원 초대 링크 발급 응답 (AI-89). inviteUrl에 원문 토큰이 들어 있다 — 이 응답 1회만 */
public record InviteCreated(long invitationId, String inviteUrl, Instant expiresAt, int maxUses) {
}
