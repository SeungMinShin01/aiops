package dev.aiops.server.model.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;

@Entity
@Table(name = "heartbeat_last")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HeartbeatLastEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(nullable = false, length = 10)
    private String env;

    @Column(name = "host_hash", nullable = false, length = 16)
    private String hostHash;

    @Column(name = "app_name", length = 200)
    private String appName;

    @Column(name = "started_at", columnDefinition = "timestamptz")
    private Instant startedAt;

    @Column(name = "last_seen_at", nullable = false, columnDefinition = "timestamptz")
    private Instant lastSeenAt;
}