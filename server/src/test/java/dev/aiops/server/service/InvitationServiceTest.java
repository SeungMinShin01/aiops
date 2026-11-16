package dev.aiops.server.service;

import dev.aiops.server.dto.AcceptResponse;
import dev.aiops.server.model.entity.MemberRole;
import dev.aiops.server.security.ApiKeyHasher;
import dev.aiops.server.support.PostgresContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 초대 발급 형식 (A와 합의한 계약, AI-84가 그대로 부른다) + 발급한 토큰으로 수락까지 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "aiops.console.base-url=https://app.example.test/")
class InvitationServiceTest extends PostgresContainerSupport {

    @Autowired InvitationService service;

    private JdbcTemplate owner;
    private long team;
    private long admin;
    private long newcomer;

    @BeforeEach
    void seed() {
        owner = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), OWNER_USER, OWNER_PASSWORD));
        owner.execute("TRUNCATE membership, invitation, admin_access_log, app_user RESTART IDENTITY CASCADE");
        team = owner.queryForObject("INSERT INTO tenant (name, status) VALUES (?, 'WAITING_OWNER') RETURNING id",
                Long.class, "inv-" + System.nanoTime());
        admin = owner.queryForObject("INSERT INTO app_user (github_id, login) VALUES (9701, 'admin') RETURNING id",
                Long.class);
        newcomer = owner.queryForObject("INSERT INTO app_user (github_id, login) VALUES (9702, 'new') RETURNING id",
                Long.class);
    }

    @Test
    void 원문은_43자_base64url이고_DB에는_해시만() {
        InvitationService.Issued issued = service.issue(team, MemberRole.OWNER, 1, Duration.ofDays(7), admin);

        assertThat(issued.rawToken()).hasSize(43).matches("[A-Za-z0-9_-]+");
        Map<String, Object> row = owner.queryForMap("SELECT * FROM invitation WHERE id = ?", issued.invitationId());
        assertThat(row.get("token_hash")).isEqualTo(ApiKeyHasher.sha256Hex(issued.rawToken()));
        assertThat(row.toString()).doesNotContain(issued.rawToken());
        assertThat(row.get("role")).isEqualTo("OWNER");
        assertThat(((Number) row.get("max_uses")).intValue()).isEqualTo(1);
        assertThat(((Number) row.get("created_by")).longValue()).isEqualTo(admin);
        assertThat(issued.toString()).doesNotContain(issued.rawToken());
    }

    @Test
    void 링크는_콘솔주소_invite_원문이고_만료는_ttl만큼() {
        Instant before = Instant.now();
        InvitationService.Issued issued = service.issue(team, MemberRole.MEMBER, 10, Duration.ofDays(7), admin);

        assertThat(issued.inviteUrl()).isEqualTo("https://app.example.test/invite/" + issued.rawToken());
        assertThat(issued.expiresAt()).isBetween(before.plus(Duration.ofDays(7)),
                Instant.now().plus(Duration.ofDays(7)));
        assertThat(issued.maxUses()).isEqualTo(10);
    }

    @Test
    void 발급할_때마다_다른_토큰() {
        String a = service.issue(team, MemberRole.MEMBER, 1, Duration.ofDays(7), admin).rawToken();
        String b = service.issue(team, MemberRole.MEMBER, 1, Duration.ofDays(7), admin).rawToken();
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void 발급한_조장_토큰으로_수락하면_OWNER_팀_ACTIVE() {
        InvitationService.Issued issued = service.issue(team, MemberRole.OWNER, 1, Duration.ofDays(7), admin);

        AcceptResponse res = service.accept(issued.rawToken(), newcomer);

        assertThat(res.role()).isEqualTo("OWNER");
        assertThat(res.next()).isEqualTo("key-guide");
        assertThat(owner.queryForObject("SELECT status FROM tenant WHERE id = ?", String.class, team))
                .isEqualTo("ACTIVE");
    }

    @Test
    void maxUses가_1보다_작으면_거부() {
        assertThatThrownBy(() -> service.issue(team, MemberRole.MEMBER, 0, Duration.ofDays(7), admin))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
