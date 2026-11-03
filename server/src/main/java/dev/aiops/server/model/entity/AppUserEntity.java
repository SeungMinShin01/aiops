package dev.aiops.server.model.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;

/** GitHub으로 로그인한 사용자 (V3). 관리자 여부는 여기 두지 않는다 (환경변수 GitHub id 목록). RLS 없음 (D-023) */
@Entity
@Table(name = "app_user")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppUserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "github_id", nullable = false, unique = true)
    private Long githubId;

    @Column(nullable = false, columnDefinition = "text")
    private String login;

    @Column(columnDefinition = "text")
    private String name;

    // 디스코드 연결 후 저장 (15단계). 디스코드 access_token은 어디에도 저장하지 않는다
    @Column(name = "discord_user_id", columnDefinition = "text")
    private String discordUserId;

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
    private Instant createdAt;

    public AppUserEntity(Long githubId, String login, String name) {
        this.githubId = githubId;
        this.login = login;
        this.name = name;
        this.createdAt = Instant.now();
    }

    public void linkDiscord(String discordUserId) {
        this.discordUserId = discordUserId;
    }
}
