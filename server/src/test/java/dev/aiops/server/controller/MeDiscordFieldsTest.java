package dev.aiops.server.controller;

import static org.assertj.core.api.Assertions.assertThat;

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
import tools.jackson.databind.json.JsonMapper;

/**
 * /api/me의 디스코드 필드 (AI-106, D-110).
 * teams[].discordStatus = 그 팀 채널 준비 상태, 맨 바깥 discordConnected = 내가 디스코드를 연결했는지.
 * 디스코드 사용자 번호·채널 번호·웹훅 주소는 응답에 넣지 않는다 (규칙 40).
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MeDiscordFieldsTest extends PostgresContainerSupport {

    @Value("${local.server.port}")
    int port;

    private final JsonMapper json = JsonMapper.builder().build();
    private JdbcTemplate owner;
    private long readyTeam;
    private long newTeam;
    private long linked;
    private long unlinked;

    @BeforeEach
    void seed() {
        owner = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), OWNER_USER, OWNER_PASSWORD));
        owner.execute("TRUNCATE membership, invitation, admin_access_log, app_user, cohort, error_event, error_group, "
                + "app_snapshot, ingest_stat, heartbeat_last, api_key, tenant RESTART IDENTITY CASCADE");
        readyTeam = owner.queryForObject("INSERT INTO tenant (name, discord_status, discord_channel_local_id, "
                + "discord_webhook_local) VALUES ('팀 A', 'READY', '880011', 'https://discord.com/api/webhooks/1/secret-token') "
                + "RETURNING id", Long.class);
        newTeam = owner.queryForObject("INSERT INTO tenant (name) VALUES ('팀 B') RETURNING id", Long.class);
        linked = owner.queryForObject("INSERT INTO app_user (github_id, login, discord_user_id) "
                + "VALUES (9701, 'linked', '770099') RETURNING id", Long.class);
        unlinked = owner.queryForObject("INSERT INTO app_user (github_id, login) VALUES (9702, 'unlinked') RETURNING id",
                Long.class);
        owner.update("INSERT INTO membership (user_id, tenant_id, role) VALUES (?, ?, 'OWNER')", linked, readyTeam);
        owner.update("INSERT INTO membership (user_id, tenant_id, role) VALUES (?, ?, 'MEMBER')", linked, newTeam);
        owner.update("INSERT INTO membership (user_id, tenant_id, role) VALUES (?, ?, 'MEMBER')", unlinked, readyTeam);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> me(long userId) {
        HttpResponse<String> res = ApiSession.login(port, userId).get("/api/me");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).doesNotContain("770099").doesNotContain("880011").doesNotContain("secret-token");
        return json.readValue(res.body(), Map.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 팀마다_채널_상태가_붙는다() {
        List<Map<String, Object>> teams = (List<Map<String, Object>>) me(linked).get("teams");

        assertThat(teams).hasSize(2);
        assertThat(teams.get(0)).containsEntry("name", "팀 A").containsEntry("discordStatus", "READY");
        assertThat(teams.get(1)).containsEntry("name", "팀 B").containsEntry("discordStatus", "NONE");
    }

    @Test
    void 디스코드를_연결한_사람은_discordConnected_true_아니면_false() {
        assertThat(me(linked)).containsEntry("discordConnected", true);
        assertThat(me(unlinked)).containsEntry("discordConnected", false);
    }
}
