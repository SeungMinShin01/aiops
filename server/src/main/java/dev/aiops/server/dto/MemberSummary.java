package dev.aiops.server.dto;

import java.time.Instant;

/** 팀원 목록 한 줄 (AI-89) */
public record MemberSummary(long userId, String login, String name, String role, Instant joinedAt) {
}
