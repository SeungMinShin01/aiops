package dev.aiops.server.service;

/**
 * 팀(tenant)이 저장됐다는 이벤트 (AI-92).
 * 13단계 AdminTeamService가 팀을 저장한 뒤 publishEvent(new TeamCreated(id))로 보낸다 (13단계 담당과 이름 맞춤).
 * 커밋이 확정된 뒤에만 DiscordProvisionListener가 받는다.
 */
public record TeamCreated(Long tenantId) {
}
