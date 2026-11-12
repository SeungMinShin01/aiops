package dev.aiops.server.dto;

/** 관리자 키 발급 요청 (AI-88). env는 "local" 또는 "prod" */
public record IssueKeyRequest(String env) {
}
