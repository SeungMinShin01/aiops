package dev.aiops.server.service;

/**
 * 팀이 재개됐다는 이벤트 (AI-94). 13단계 AdminTeamService.resume이 tenant.status=ACTIVE 저장 뒤 발행한다
 * (13단계 담당과 이름 맞춤). 리스너가 provision을 다시 돌려 웹훅만 새로 만든다 (역할·채널은 저장된 id 재사용).
 */
public record TeamResumed(Long tenantId) {
}
