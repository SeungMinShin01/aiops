package dev.aiops.server.model.entity;

/** AI 설명 평가 👍/👎. DB는 text + CHECK, JPA는 @Enumerated(STRING) */
public enum Feedback {
    UP, DOWN
}
