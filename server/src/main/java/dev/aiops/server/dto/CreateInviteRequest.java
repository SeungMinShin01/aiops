package dev.aiops.server.dto;

/** 팀원 초대 링크 발급 요청 (AI-89). maxUses 1~50, 비우면 10 */
public record CreateInviteRequest(Integer maxUses) {
}
