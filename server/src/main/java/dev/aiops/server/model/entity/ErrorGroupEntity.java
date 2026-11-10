package dev.aiops.server.model.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

@Entity
@Table(name = "error_group")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ErrorGroupEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 64)
    private String fingerprint;

    @Column(name = "exception_type", nullable = false, length = 300)
    private String exceptionType;

    @Column(name = "top_frame", length = 500)
    private String topFrame;

    @Column(name = "first_env", nullable = false, length = 10)
    private String firstEnv;

    @Column(name = "first_seen_at", nullable = false, columnDefinition = "timestamptz")
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false, columnDefinition = "timestamptz")
    private Instant lastSeenAt;

    // 횟수 증가는 UPDATE 쿼리로 (동시에 들어오면 엔티티 count++는 유실됨, 6단계)
    @Column(name = "occurrence_count", nullable = false)
    private long occurrenceCount = 1;

    @Column(name = "ai_explanation", columnDefinition = "text")
    private String aiExplanation;

    @Column(name = "ai_explained_at", columnDefinition = "timestamptz")
    private Instant aiExplainedAt;

    // ---- V3 (콘솔) ----

    // IGNORED면 재발해도 알림 없음 (16단계)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private GroupStatus status = GroupStatus.OPEN;

    // AI 설명 평가 👍/👎. 평가 전에는 null
    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "text")
    private Feedback feedback;

    public ErrorGroupEntity(Long tenantId, String fingerprint, String exceptionType,
            String topFrame, String firstEnv) {
        this.tenantId = tenantId;
        this.fingerprint = fingerprint;
        this.exceptionType = exceptionType;
        this.topFrame = topFrame;
        this.firstEnv = firstEnv;
        Instant now = Instant.now();
        this.firstSeenAt = now;
        this.lastSeenAt = now;
    }

    public void explain(String explanation) {
        this.aiExplanation = explanation;
        this.aiExplainedAt = Instant.now();
    }
}