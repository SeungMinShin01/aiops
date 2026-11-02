package dev.aiops.server.model.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnTransformer;
import java.time.Instant;

@Entity
@Table(name = "app_snapshot")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(nullable = false, length = 10)
    private String env;

    @Column(name = "host_hash", length = 16)
    private String hostHash;

    // protocol HelloRequest JSON 원문
    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String payload;

    @Column(name = "received_at", nullable = false, columnDefinition = "timestamptz")
    private Instant receivedAt;

    public AppSnapshotEntity(Long tenantId, String env, String hostHash,
            String payload, Instant receivedAt) {
        this.tenantId = tenantId;
        this.env = env;
        this.hostHash = hostHash;
        this.payload = payload;
        this.receivedAt = receivedAt;
    }
}