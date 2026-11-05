package dev.aiops.server.model.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;

/** 관리자 API 호출 기록 (V3, 관리자 전용). 관리자 호출마다 1행. RLS 없음 (D-023) */
@Entity
@Table(name = "admin_access_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdminAccessLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "admin_user_id", nullable = false)
    private Long adminUserId;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(nullable = false, columnDefinition = "text")
    private String action;

    @Column(nullable = false, columnDefinition = "timestamptz")
    private Instant at;

    public AdminAccessLogEntity(Long adminUserId, Long tenantId, String action) {
        this.adminUserId = adminUserId;
        this.tenantId = tenantId;
        this.action = action;
        this.at = Instant.now();
    }
}
