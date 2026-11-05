package dev.aiops.server.service;

import dev.aiops.server.client.DiscordApiClient;
import dev.aiops.server.client.DiscordApiException;
import dev.aiops.server.client.DiscordOAuthClient;
import dev.aiops.server.model.entity.AppUserEntity;
import dev.aiops.server.model.entity.DiscordStatus;
import dev.aiops.server.model.entity.MemberRole;
import dev.aiops.server.model.entity.MembershipEntity;
import dev.aiops.server.model.entity.TenantEntity;
import dev.aiops.server.model.repository.AppUserRepository;
import dev.aiops.server.model.repository.MembershipRepository;
import dev.aiops.server.model.repository.TenantRepository;
import dev.aiops.server.security.InvalidOAuthStateException;
import dev.aiops.server.support.PostgresContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 팀원 디스코드 연결 (AI-93). 진짜 DB(팀·사용자·멤버십) + 가짜 디스코드(OAuth·봇 API 목).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"aiops.state-secret=test-state-secret", "aiops.discord.guild-id=111",
                "aiops.discord.bot-token=fake-bot-token"})
@ExtendWith(OutputCaptureExtension.class)
class DiscordJoinServiceTest extends PostgresContainerSupport {

    static final String USER_TOKEN = "user-token-should-not-leak";

    @MockitoBean DiscordOAuthClient oauth;
    @MockitoBean DiscordApiClient api;

    @Autowired DiscordJoinService joinService;
    @Autowired DiscordProvisioner provisioner;
    @Autowired TenantRepository tenants;
    @Autowired AppUserRepository appUsers;
    @Autowired MembershipRepository memberships;

    JdbcTemplate owner;
    Long teamId;
    Long userId;

    @BeforeEach
    void setUp() {
        owner = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), OWNER_USER, OWNER_PASSWORD));
        owner.execute("TRUNCATE membership, invitation, app_user, error_event, error_group, app_snapshot, ingest_stat, "
                + "heartbeat_last, api_key, tenant RESTART IDENTITY CASCADE");

        teamId = tenants.save(new TenantEntity("Shop Team")).getId();
        tenants.updateDiscordRoleId(teamId, "role-1");
        tenants.updateDiscordStatus(teamId, DiscordStatus.READY, null);
        userId = appUsers.save(new AppUserEntity(1001L, "member1", null)).getId();
        memberships.save(new MembershipEntity(userId, teamId, MemberRole.MEMBER));

        when(oauth.exchange("good-code")).thenReturn(USER_TOKEN);
        when(oauth.me(USER_TOKEN)).thenReturn("discord-555");
        when(api.addMember("discord-555", USER_TOKEN, List.of("role-1"))).thenReturn(DiscordApiClient.AddMemberResult.ADDED);
    }

    private String discordUserIdOf(Long id) {
        return appUsers.findById(id).orElseThrow().getDiscordUserId();
    }

    @Test
    void 연결하면_서버에_넣고_팀_역할을_붙이고_디스코드_id만_저장한다(CapturedOutput output) {
        String state = joinService.createState(userId, teamId);

        DiscordJoinService.Result result = joinService.complete(userId, "good-code", state);

        assertThat(result).isEqualTo(DiscordJoinService.Result.OK);
        verify(api).addMember("discord-555", USER_TOKEN, List.of("role-1"));
        verify(api, never()).addRole(anyString(), anyString());
        assertThat(discordUserIdOf(userId)).isEqualTo("discord-555");

        // 토큰은 어디에도 없다: app_user 행 전체·로그
        Map<String, Object> row = owner.queryForMap("SELECT * FROM app_user WHERE id = ?", userId);
        assertThat(row.values().toString()).doesNotContain(USER_TOKEN);
        assertThat(output).doesNotContain(USER_TOKEN);
    }

    @Test
    void 이미_서버에_있던_사람은_역할을_따로_붙인다() {
        when(api.addMember("discord-555", USER_TOKEN, List.of("role-1")))
                .thenReturn(DiscordApiClient.AddMemberResult.ALREADY_MEMBER);

        DiscordJoinService.Result result = joinService.complete(userId, "good-code", joinService.createState(userId, teamId));

        assertThat(result).isEqualTo(DiscordJoinService.Result.OK);
        verify(api).addRole("discord-555", "role-1");
    }

    @Test
    void 위조한_state는_400() {
        assertThatThrownBy(() -> joinService.complete(userId, "good-code", "forged.state"))
                .isInstanceOf(InvalidOAuthStateException.class);
        verify(oauth, never()).exchange(anyString());
    }

    @Test
    void state의_사용자가_세션_사용자와_다르면_400() {
        Long otherUser = appUsers.save(new AppUserEntity(2002L, "other", null)).getId();
        String stateOfOther = joinService.createState(otherUser, teamId);

        assertThatThrownBy(() -> joinService.complete(userId, "good-code", stateOfOther))
                .isInstanceOf(InvalidOAuthStateException.class);
    }

    @Test
    void 팀_멤버가_아니면_NOT_MEMBER() {
        Long outsider = appUsers.save(new AppUserEntity(3003L, "outsider", null)).getId();

        assertThat(joinService.checkConnectable(outsider, teamId)).isEqualTo(DiscordJoinService.Result.NOT_MEMBER);
        assertThat(joinService.complete(outsider, "good-code", joinService.createState(outsider, teamId)))
                .isEqualTo(DiscordJoinService.Result.NOT_MEMBER);
        verify(oauth, never()).exchange(anyString());
    }

    @Test
    void 팀_채널이_READY가_아니면_NOT_READY() {
        tenants.updateDiscordStatus(teamId, DiscordStatus.FAILED, "channel-local:500");

        assertThat(joinService.checkConnectable(userId, teamId)).isEqualTo(DiscordJoinService.Result.NOT_READY);
    }

    @Test
    void 다른_사용자에_이미_연결된_디스코드_계정이면_거부() {
        AppUserEntity other = appUsers.save(new AppUserEntity(2002L, "other", null));
        other.linkDiscord("discord-555");
        appUsers.save(other);

        DiscordJoinService.Result result = joinService.complete(userId, "good-code", joinService.createState(userId, teamId));

        assertThat(result).isEqualTo(DiscordJoinService.Result.DISCORD_ACCOUNT_IN_USE);
        verify(api, never()).addMember(anyString(), anyString(), any());
        assertThat(discordUserIdOf(userId)).isNull();
    }

    @Test
    void 디스코드_호출이_실패하면_FAILED_아무것도_저장_안_함(CapturedOutput output) {
        when(oauth.exchange("good-code")).thenThrow(new DiscordApiException(400, "oauthExchange"));

        DiscordJoinService.Result result = joinService.complete(userId, "good-code", joinService.createState(userId, teamId));

        assertThat(result).isEqualTo(DiscordJoinService.Result.FAILED);
        assertThat(discordUserIdOf(userId)).isNull();
        assertThat(output).contains("디스코드 연결 실패").contains("단계=exchange");
    }

    @Test
    void 내보내면_팀_역할을_회수한다() {
        AppUserEntity user = appUsers.findById(userId).orElseThrow();
        user.linkDiscord("discord-555");
        appUsers.save(user);

        provisioner.revokeRole(teamId, userId);

        verify(api).removeRole("discord-555", "role-1");
    }

    @Test
    void 디스코드를_연결하지_않은_사람은_회수할_게_없다() {
        provisioner.revokeRole(teamId, userId);

        verify(api, never()).removeRole(anyString(), anyString());
    }

    @Test
    void 역할_회수가_실패해도_예외를_던지지_않는다() {
        AppUserEntity user = appUsers.findById(userId).orElseThrow();
        user.linkDiscord("discord-555");
        appUsers.save(user);
        doThrow(new DiscordApiException(500, "removeRole")).when(api).removeRole("discord-555", "role-1");

        provisioner.revokeRole(teamId, userId); // 내보내기는 성공해야 하므로 예외 없음
    }
}
