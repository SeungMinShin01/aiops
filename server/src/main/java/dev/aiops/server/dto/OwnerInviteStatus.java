package dev.aiops.server.dto;

import java.time.Instant;

// AI-84 : 지금 유효한 조장 링크의 상태. 원문·해시는 없음. used = 이미 조장이 이 링크로 합류했는지
public record OwnerInviteStatus(Instant expiresAt, boolean used) {
}