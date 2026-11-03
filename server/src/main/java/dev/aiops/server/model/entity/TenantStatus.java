package dev.aiops.server.model.entity;

/** 팀 상태. WAITING_OWNER = 관리자가 만들고 조장 합류 전. DB는 text + CHECK, JPA는 @Enumerated(STRING) */
public enum TenantStatus {
    WAITING_OWNER, ACTIVE, SUSPENDED
}
