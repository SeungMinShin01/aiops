package dev.aiops.server.dto;

/**
 * 초대 수락 응답 (AI-105). next는 콘솔(17단계)이 이동할 곳.
 * - OWNER → "key-guide" (키는 관리자가 디스코드 DM으로 보낸다는 안내, D-092)
 * - MEMBER → "errors"
 * 프론트가 role을 보고 다른 API를 고르지 않게, 갈 곳을 백엔드가 정해 준다 (규칙 13).
 */
public record AcceptResponse(long teamId, String teamName, String role, String next) {
}
