package dev.aiops.server.controller;

import dev.aiops.server.client.DiscordApiClient;
import dev.aiops.server.client.DiscordApiException;
import dev.aiops.server.client.DiscordOAuthClient;
import dev.aiops.server.security.OAuthState;
import dev.aiops.server.support.PostgresContainerSupport;
import dev.aiops.server.support.TestLogin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 디스코드 연결 컨트롤러 (AI-93). 진짜 서버 + TestLogin 세션, 디스코드 쪽(OAuth·봇 API)만 가짜.
 * state는 진짜 OAuthState로 만들고 검증한다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "aiops.state-secret=" + DiscordJoinControllerTest.SECRET,
        "aiops.discord.client-id=test-client-id",
        "aiops.discord.redirect-uri=http://localhost:8080/api/discord/callback"
})
class DiscordJoinControllerTest extends PostgresContainerSupport {

    static final String SECRET = "discord-join-controller-test-secret";

    @Value("${local.server.port}")
    int port;

    @Autowired OAuthState oauthState;
    @MockitoBean DiscordOAuthClient oauth;
    @MockitoBean DiscordApiClient api;

    private JdbcTemplate owner;
    private TestLogin login;
    private long readyTeam;
    private long notReadyTeam;
    private long member;
    private long stranger;

    @BeforeEach
    void seed() {
        owner = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), OWNER_USER, OWNER_PASSWORD));
        login = new TestLogin(port);
        owner.execute("TRUNCATE membership, invitation, admin_access_log, app_user, cohort, error_event, error_group, "
                + "app_snapshot, ingest_stat, heartbeat_last, api_key, tenant RESTART IDENTITY CASCADE");

        readyTeam = owner.queryForObject("INSERT INTO tenant (name, discord_status, discord_role_id) "
                + "VALUES ('준비된 팀', 'READY', 'role-1') RETURNING id", Long.class);
        notReadyTeam = owner.queryForObject("INSERT INTO tenant (name) VALUES ('준비 전 팀') RETURNING id", Long.class);
        member = owner.queryForObject("INSERT INTO app_user (github_id, login) VALUES (9501, 'member') RETURNING id",
                Long.class);
        stranger = owner.queryForObject("INSERT INTO app_user (github_id, login) VALUES (9502, 'stranger') RETURNING id",
                Long.class);
        owner.update("INSERT INTO membership (user_id, tenant_id, role) VALUES (?, ?, 'MEMBER')", member, readyTeam);
        owner.update("INSERT INTO membership (user_id, tenant_id, role) VALUES (?, ?, 'MEMBER')", member, notReadyTeam);
    }

    private static String callback(String code, String state) {
        return "/api/discord/callback?code=" + code + "&state=" + state;
    }

    /** 서명은 맞지만 만료 시각이 지난 state (OAuthState와 같은 형식으로 직접 만든다) */
    private static String expiredState(long userId, long teamId) throws Exception {
        String payload = userId + ":" + teamId + ":" + Instant.now().minusSeconds(60).getEpochSecond();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        return b64.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "."
                + b64.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    // ---- connect ----

    @Test
    void 세션_없으면_401() {
        assertThat(login.anonymous().get("/api/discord/connect?teamId=" + readyTeam).statusCode()).isEqualTo(401);
    }

    @Test
    void 비멤버_connect는_403() {
        assertThat(login.as(stranger).get("/api/discord/connect?teamId=" + readyTeam).statusCode()).isEqualTo(403);
    }

    @Test
    void 팀_채널이_READY가_아니면_409() {
        assertThat(login.as(member).get("/api/discord/connect?teamId=" + notReadyTeam).statusCode()).isEqualTo(409);
    }

    @Test
    void 통과하면_디스코드_동의_화면으로_302() {
        HttpResponse<String> res = login.as(member).get("/api/discord/connect?teamId=" + readyTeam);

        assertThat(res.statusCode()).isEqualTo(302);
        String location = res.headers().firstValue("location").orElseThrow();
        assertThat(location).startsWith("https://discord.com/oauth2/authorize?")
                .contains("client_id=test-client-id")
                .contains("response_type=code")
                .contains("scope=identify%20guilds.join")
                .contains("prompt=consent")
                .contains("state=");
        // 서버가 서명한 state가 실려 간다
        String state = location.replaceAll(".*[?&]state=([^&]+).*", "$1");
        assertThat(oauthState.verify(state).teamId()).isEqualTo(readyTeam);
        assertThat(oauthState.verify(state).userId()).isEqualTo(member);
    }

    // ---- callback ----

    @Test
    void 위조된_state는_400() {
        assertThat(login.as(member).get(callback("c", "forged.state")).statusCode()).isEqualTo(400);
        verify(oauth, never()).exchange(anyString());
    }

    @Test
    void 만료된_state는_400() throws Exception {
        assertThat(login.as(member).get(callback("c", expiredState(member, readyTeam))).statusCode()).isEqualTo(400);
        verify(oauth, never()).exchange(anyString());
    }

    @Test
    void 다른_사용자의_state는_400() {
        String othersState = oauthState.create(stranger, readyTeam);
        assertThat(login.as(member).get(callback("c", othersState)).statusCode()).isEqualTo(400);
        verify(oauth, never()).exchange(anyString());
    }

    @Test
    void 콜백_성공이면_역할_부여_후_discord_user_id_저장_토큰은_어디에도_없음() {
        when(oauth.exchange("the-code")).thenReturn("tok-123");
        when(oauth.me("tok-123")).thenReturn("d-555");
        when(api.addMember(eq("d-555"), eq("tok-123"), eq(List.of("role-1"))))
                .thenReturn(DiscordApiClient.AddMemberResult.ADDED);

        HttpResponse<String> res = login.as(member).get(callback("the-code", oauthState.create(member, readyTeam)));

        assertThat(res.statusCode()).isEqualTo(302);
        assertThat(res.headers().firstValue("location").orElseThrow())
                .endsWith("/teams/" + readyTeam + "/settings?discord=connected");
        verify(api).addMember(eq("d-555"), eq("tok-123"), eq(List.of("role-1")));

        Map<String, Object> row = owner.queryForMap("SELECT * FROM app_user WHERE id = ?", member);
        assertThat(row.get("discord_user_id")).isEqualTo("d-555");
        assertThat(row.toString()).doesNotContain("tok-123");
        assertThat(res.body()).doesNotContain("tok-123");
    }

    @Test
    void 디스코드_호출이_실패하면_failed로_돌려보낸다() {
        when(oauth.exchange(anyString())).thenThrow(new DiscordApiException(500, "oauth-token"));

        HttpResponse<String> res = login.as(member).get(callback("bad", oauthState.create(member, readyTeam)));

        assertThat(res.statusCode()).isEqualTo(302);
        assertThat(res.headers().firstValue("location").orElseThrow()).endsWith("?discord=failed");
        verify(api, never()).addMember(anyString(), any(), anyList());
        assertThat(owner.queryForObject("SELECT discord_user_id FROM app_user WHERE id = ?", String.class, member))
                .isNull();
    }

    @Test
    void 디스코드_동의를_취소하면_code_없이_와도_failed() {
        HttpResponse<String> res = login.as(member).get(
                "/api/discord/callback?error=access_denied&state=" + oauthState.create(member, readyTeam));

        assertThat(res.statusCode()).isEqualTo(302);
        assertThat(res.headers().firstValue("location").orElseThrow()).endsWith("?discord=failed");
        verify(oauth, never()).exchange(anyString());
    }
}
