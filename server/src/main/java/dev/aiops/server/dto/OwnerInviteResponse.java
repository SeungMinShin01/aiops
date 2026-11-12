package dev.aiops.server.dto;

import java.time.Instant;

// AI-84 : 조장 초대 링크 발급 응답. inviteUrl 에만 토큰 원문이 들어 있고, 이 응답이 원문을 볼 수 있는 유일한 곳이다 (규칙 10)
public record OwnerInviteResponse(String inviteUrl, Instant expiresAt) {
}