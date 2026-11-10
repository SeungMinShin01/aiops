package dev.aiops.server.model.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "tenant")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    // 비밀값. 응답 DTO에 그대로 쓰지 않는다 (규칙 10). 알림(8단계)이 읽어야 해서 getter는 둔다
    @Column(name = "discord_webhook_local", columnDefinition = "text")
    private String discordWebhookLocal;

    @Column(name = "discord_webhook_prod", columnDefinition = "text")
    private String discordWebhookProd;

    @Column(name = "notify_local", nullable = false)
    private boolean notifyLocal = true;

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    // ---- V3 (콘솔) ----

    @Column(name = "cohort_id")
    private Long cohortId;

    @Column(name = "project_end_date")
    private LocalDate projectEndDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "text")
    private TenantStatus status = TenantStatus.ACTIVE;

    // 디스코드 자원 id (15단계 Provisioner가 채운다. 웹훅 주소는 위 V1 컬럼)
    @Column(name = "discord_role_id", columnDefinition = "text")
    private String discordRoleId;

    @Column(name = "discord_category_id", columnDefinition = "text")
    private String discordCategoryId;

    @Column(name = "discord_channel_local_id", columnDefinition = "text")
    private String discordChannelLocalId;

    @Column(name = "discord_channel_prod_id", columnDefinition = "text")
    private String discordChannelProdId;

    @Enumerated(EnumType.STRING)
    @Column(name = "discord_status", nullable = false, columnDefinition = "text")
    private DiscordStatus discordStatus = DiscordStatus.NONE;

    // 채널 자동 생성 실패 단계 "<단계명>:<status>" (AI-92). 웹훅 url·토큰은 절대 넣지 않는다
    @Column(name = "discord_error", length = 100)
    private String discordError;

    public TenantEntity(String name) {
        this.name = name;
        this.createdAt = Instant.now();
    }
}