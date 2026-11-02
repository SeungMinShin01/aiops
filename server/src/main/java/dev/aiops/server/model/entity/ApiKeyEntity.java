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

    public ApiKeyEntity(Long tenantId, String keyHash, String env) {
        this.tenantId = tenantId;
        this.keyHash = keyHash;
        this.env = env;
        this.createdAt = Instant.now();
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