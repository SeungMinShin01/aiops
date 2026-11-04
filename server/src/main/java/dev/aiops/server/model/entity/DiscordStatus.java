package dev.aiops.server.model.entity;

/**
 * 디스코드 채널 자동 생성 상태 (15단계). DB는 text + CHECK, JPA는 @Enumerated(STRING)
 * NONE = 아직 / READY = 역할·채널·웹훅 다 있음 / FAILED = 생성 중 실패(재시도 가능) / SUSPENDED = 팀 정지로 웹훅만 삭제됨 (AI-94)
 */
public enum DiscordStatus {
    NONE, READY, FAILED, SUSPENDED
}
