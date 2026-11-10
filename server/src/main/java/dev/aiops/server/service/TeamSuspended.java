package dev.aiops.server.service;

/**
 * 팀이 정지됐다는 이벤트 (AI-94). 13단계 AdminTeamService.suspend가 tenant.status=SUSPENDED 저장 뒤 발행한다
 * (13단계 담당과 이름 맞춤). 커밋이 확정된 뒤에만 DiscordProvisionListener가 받는다.
 */
public record TeamSuspended(Long tenantId) {
}
