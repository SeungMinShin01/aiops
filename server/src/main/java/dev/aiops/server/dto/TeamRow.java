package dev.aiops.server.dto;

import dev.aiops.server.model.entity.DiscordStatus;
import dev.aiops.server.model.entity.TenantStatus;
import java.time.LocalDate;

// AI-83 : 팀 한 줄. 일괄 생성 응답과 GET /api/admin/teams 가 같이 씀
// 일괄 생성 응답에서는 ownerLogin 이 항상 null (방금 만든 팀엔 조장이 없음). 조장이 없으면 목록에서도 null
public record TeamRow(Long teamId, String name, TenantStatus status, DiscordStatus discordStatus,
                      LocalDate projectEndDate, String ownerLogin) {
}