package dev.aiops.server.model.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDate;

@Entity
@Table(name = "ingest_stat")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IngestStatEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(nullable = false, length = 10)
    private String env;

    @Column(nullable = false)
    private LocalDate day;

    @Column(name = "received_count", nullable = false)
    private long receivedCount;

    @Column(name = "dropped_count", nullable = false)
    private long droppedCount;
}