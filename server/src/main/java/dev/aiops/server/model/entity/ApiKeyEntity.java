package dev.aiops.server.model.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

@Entity
@Table(name = "api_key")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApiKeyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    // CHAR(64). 원문이 아니라 SHA-256 hex. 조회는 리포지토리로만 → getter 제외
    @Getter(AccessLevel.NONE)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "key_hash", nullable = false, unique = true, length = 64)
    private String keyHash;

    @Column(nullable = false, length = 10)
    private String env;

    @Column(name = "revoked_at", columnDefinition = "timestamptz")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    // ---- V3 (콘솔) ----

    // 콘솔 키 목록 표시용 앞자리 (예: aiops_l_ab12). 원문이 아니고 복원 불가능한 길이
    @Column(name = "key_prefix", length = 16)
    private String keyPrefix;

    @Column(name = "created_by")
    private Long createdBy;

    // 키 캐시(AI-74)가 비었을 때(DB 조회 시)만 갱신 → 키마다 1분에 최대 1회 (AI-88, ApiKeyResolver)
    @Column(name = "last_used_at", columnDefinition = "timestamptz")
    private Instant lastUsedAt;

    public ApiKeyEntity(Long tenantId, String keyHash, String env) {
        this.tenantId = tenantId;
        this.keyHash = keyHash;
        this.env = env;
        this.createdAt = Instant.now();
    }

    // 관리자 발급 (AI-88): 표시용 앞자리·발급자까지
    public ApiKeyEntity(Long tenantId, String keyHash, String env, String keyPrefix, Long createdBy) {
        this(tenantId, keyHash, env);
        this.keyPrefix = keyPrefix;
        this.createdBy = createdBy;
    }

    public void revoke() {
        if (revokedAt == null) {
            revokedAt = Instant.now();
        }
    }

    public boolean isActive() {
        return revokedAt == null;
    }
}