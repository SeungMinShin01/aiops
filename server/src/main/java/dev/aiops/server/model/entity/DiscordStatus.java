package dev.aiops.server.model.entity;

/** 디스코드 채널 자동 생성 상태 (15단계). DB는 text + CHECK, JPA는 @Enumerated(STRING) */
public enum DiscordStatus {
    NONE, READY, FAILED
}
