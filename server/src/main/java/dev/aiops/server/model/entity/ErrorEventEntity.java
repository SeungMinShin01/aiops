package dev.aiops.server.model.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnTransformer;
import java.time.Instant;

@Entity
@Table(name = "error_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ErrorEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(nullable = false, length = 10)
    private String env;

    @Column(name = "group_id")
    private Long groupId;

    // protocol ErrorEvent JSON 원문. Jackson을 거치지 않고 문자열 → jsonb 캐스팅
    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String payload;

    @Column(name = "app_name", length = 200)
    private String appName;

    @Column(name = "app_commit", length = 64)
    private String appCommit;

    @Column(name = "host_hash", length = 16)
    private String hostHash;

    @Column(name = "received_at", nullable = false, columnDefinition = "timestamptz")
    private Instant receivedAt;

    @Column(nullable = false)
    private boolean processed = false;

    public ErrorEventEntity(Long tenantId, String env, String payload,
            String appName, String appCommit, String hostHash) {
        this.tenantId = tenantId;
        this.env = env;
        this.payload = payload;
        this.appName = appName;
        this.appCommit = appCommit;
        this.hostHash = hostHash;
        this.receivedAt = Instant.now();
    }

    public void assignGroup(Long groupId) {
        this.groupId = groupId;
        this.processed = true;
    }
}