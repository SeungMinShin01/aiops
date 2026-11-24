package dev.aiops.server.model.entity;

/** 에러 그룹 상태 (콘솔). IGNORED면 재발해도 알림 없음. DB는 text + CHECK, JPA는 @Enumerated(STRING) */
public enum GroupStatus {
    OPEN, RESOLVED, IGNORED
}
