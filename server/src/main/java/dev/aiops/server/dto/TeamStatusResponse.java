package dev.aiops.server.dto;

import dev.aiops.server.model.entity.TenantStatus;

// AI-85 : 팀 정지·재개 응답. { "teamId": 7, "status": "SUSPENDED" }
public record TeamStatusResponse(Long teamId, TenantStatus status) {
}