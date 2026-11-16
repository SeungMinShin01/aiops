package dev.aiops.server.controller.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.aiops.server.service.DiscordProvisioner;
import dev.aiops.server.support.ApiSession;
import dev.aiops.server.support.PostgresContainerSupport;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

/**
 * 디스코드 채널 재시도 API (AI-92). 진짜 서버 + 테스트 세션, DiscordProvisioner만 가짜.
 * provision 자체(멱등·이어서 만들기·잠금)는 DiscordProvisionerTest가 확인한다. 여기서는 권한·응답·기록만.
 * 관리자 GitHub id 9501, 일반 사용자 9502.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "aiops.admin.github-ids=9501")
class AdminDiscordRetryTest extends PostgresContainerSupport {

    @Value("${local.server.port}")
    int port;

    @MockitoBean DiscordProvisioner provisioner;

    private final JsonMapper json = JsonMapper.builder().build();
    private JdbcTemplate owner;
    private long admin;
    private long captain;
    private long team;

    @BeforeEach
    void seed() {
        owner = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), OWNER_USER, OWNER_PASSWORD));
        owner.execute("TRUNCATE membership, invitation, admin_access_log, app_user, cohort, error_event, error_group, "
                + "app_snapshot, ingest_stat, heartbeat_last, api_key, tenant RESTART IDENTITY CASCADE");
        admin = owner.queryForObject("INSERT INTO app_user (github_id, login) VALUES (9501, 'admin') RETURNING id", Long.class);
        captain = owner.queryForObject("INSERT INTO app_user (github_id, login) VALUES (9502, 'captain') RETURNING id", Long.class);
        team = owner.queryForObject("INSERT INTO tenant (name, discord_status, discord_error, discord_webhook_local) "
                + "VALUES ('3기-1조', 'FAILED', 'create-channel-prod:403', 'https://discord.com/api/webhooks/1/secret-token') "
                + "RETURNING id", Long.class);
        owner.update("INSERT INTO membership (user_id, tenant_id, role) VALUES (?, ?, 'OWNER')", captain, team);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(HttpResponse<String> res) {
        return json.readValue(res.body(), Map.class);
    }

    private HttpResponse<String> retry(long userId, long teamId) {
        return ApiSession.login(port, userId).post("/api/admin/teams/" + teamId + "/discord/retry", "{}");
    }

    @Test
    void 성공하면_200과_지금_채널_상태() {
        when(provisioner.provision(team)).thenAnswer(inv -> {
            owner.update("UPDATE tenant SET discord_status = 'READY', discord_error = NULL WHERE id = ?", team);
            return DiscordProvisioner.Result.READY;
        });

        HttpResponse<String> res = retry(admin, team);

        assertThat(res.statusCode()).isEqualTo(200);
        Map<String, Object> body = map(res);
        assertThat(body).containsEntry("result", "READY").containsEntry("discordStatus", "READY");
        assertThat(body.get("discordError")).isNull();
        verify(provisioner).provision(team);
    }

    @Test
    void 다시_실패하면_200에_FAILED와_실패_단계_웹훅_주소는_없음() {
        when(provisioner.provision(team)).thenReturn(DiscordProvisioner.Result.FAILED);

        HttpResponse<String> res = retry(admin, team);

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(map(res)).containsEntry("result", "FAILED")
                .containsEntry("discordStatus", "FAILED")
                .containsEntry("discordError", "create-channel-prod:403");
        assertThat(res.body()).doesNotContain("secret-token").doesNotContain("webhooks");
    }

    @Test
    void 이미_준비된_팀은_ALREADY_READY() {
        owner.update("UPDATE tenant SET discord_status = 'READY', discord_error = NULL WHERE id = ?", team);
        when(provisioner.provision(team)).thenReturn(DiscordProvisioner.Result.ALREADY_READY);

        HttpResponse<String> res = retry(admin, team);

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(map(res)).containsEntry("result", "ALREADY_READY").containsEntry("discordStatus", "READY");
    }

    @Test
    void 정지_팀은_409_진행_중이면_409_봇_설정_없으면_503_없는_팀은_404() {
        when(provisioner.provision(team)).thenReturn(DiscordProvisioner.Result.SUSPENDED);
        HttpResponse<String> suspended = retry(admin, team);
        assertThat(suspended.statusCode()).isEqualTo(409);
        assertThat(map(suspended)).containsEntry("error", "TEAM_SUSPENDED");

        when(provisioner.provision(team)).thenReturn(DiscordProvisioner.Result.IN_PROGRESS);
        HttpResponse<String> inProgress = retry(admin, team);
        assertThat(inProgress.statusCode()).isEqualTo(409);
        assertThat(map(inProgress)).containsEntry("error", "IN_PROGRESS");

        when(provisioner.provision(team)).thenReturn(DiscordProvisioner.Result.NOT_CONFIGURED);
        HttpResponse<String> notConfigured = retry(admin, team);
        assertThat(notConfigured.statusCode()).isEqualTo(503);
        assertThat(map(notConfigured)).containsEntry("error", "DISCORD_NOT_CONFIGURED");

        when(provisioner.provision(999L)).thenReturn(DiscordProvisioner.Result.NOT_FOUND);
        HttpResponse<String> missing = retry(admin, 999L);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(map(missing)).containsEntry("error", "TEAM_NOT_FOUND");
    }

    @Test
    void 관리자가_아니면_조장이어도_403이고_디스코드를_부르지_않는다() {
        HttpResponse<String> res = retry(captain, team);

        assertThat(res.statusCode()).isEqualTo(403);
        verify(provisioner, never()).provision(anyLong());
    }

    @Test
    void 관리자_호출은_접근_기록에_남는다() {
        when(provisioner.provision(team)).thenReturn(DiscordProvisioner.Result.FAILED);

        retry(admin, team);

        List<Map<String, Object>> logs = owner.queryForList("SELECT * FROM admin_access_log");
        assertThat(logs).isNotEmpty();
        assertThat(logs.toString()).contains("discord/retry").doesNotContain("secret-token");
    }
}
