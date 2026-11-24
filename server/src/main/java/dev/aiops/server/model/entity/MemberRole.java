package dev.aiops.server.model.entity;

/** 팀 안 역할. OWNER = 조장. DB는 text + CHECK, JPA는 @Enumerated(STRING) */
public enum MemberRole {
    OWNER, MEMBER
}
