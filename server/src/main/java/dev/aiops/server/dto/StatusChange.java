package dev.aiops.server.dto;

// AI-97 : PATCH /errors/{groupId}/status 요청 본문 { "status": "RESOLVED" }. 허용값 확인은 서비스가 한다 (OPEN, RESOLVED, IGNORED)
public record StatusChange(String status) {
}