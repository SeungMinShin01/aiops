package dev.aiops.server.dto;

/** GET /api/teams/{teamId}/connection 응답 (AI-98). { local: {...}, prod: {...} } */
public record ConnectionResponse(ConnectionStatus local, ConnectionStatus prod) {
}
