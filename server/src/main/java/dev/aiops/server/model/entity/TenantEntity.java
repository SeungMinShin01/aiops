package dev.aiops.server.model.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;

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

    public TenantEntity(String name) {
        this.name = name;
        this.createdAt = Instant.now();
    }
}