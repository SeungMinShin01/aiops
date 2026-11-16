package dev.aiops.server.controller;

import dev.aiops.server.support.ApiSession;
import dev.aiops.server.support.PostgresContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 팀원 초대·목록·내보내기 (AI-89). 진짜 서버 + 테스트 세션.
 * 조장 alice, 팀원 bob·carol (팀 A), 다른 팀 조장 dave (팀 B).
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "aiops.console.base-url=https://app.example.test")
class MemberRemoveTest extends PostgresContainerSupport {

    @Value("${local.server.port}")
    int port;

    private final JsonMapper json = JsonMapper.builder().build();
    private JdbcTemplate owner;
    private long team;
    private long otherTeam;
    private long alice;
    private long bob;
    private long carol;
    private long dave;

    @BeforeEach
    void seed() {
        owner = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), OWNER_USER, OWNER_PASSWORD));
        owner.execute("TRUNCATE membership, invitation, admin_access_log, app_user, cohort, error_event, error_group, "
                + "app_snapshot, ingest_stat, heartbeat_last, api_key, tenant RESTART IDENTITY CASCADE");
        team = owner.queryForObject("INSERT INTO tenant (name) VALUES ('팀 A') RETURNING id", Long.class);
        otherTeam = owner.queryForObject("INSERT INTO tenant (name) VALUES ('팀 B') RETURNING id", Long.class);
        alice = user(9801, "alice");
        bob = user(9802, "bob");
        carol = user(9803, "carol");
        dave = user(9804, "dave");
        member(alice, team, "OWNER");
        member(bob, team, "MEMBER");
        member(carol, team, "MEMBER");
        member(dave, otherTeam, "OWNER");
    }

    private long user(long githubId, String login) {
        return owner.queryForObject("INSERT INTO app_user (github_id, login) VALUES (?, ?) RETURNING id",
                Long.class, githubId, login);
    }

    private void member(long userId, long tenantId, String role) {
        owner.update("INSERT INTO membership (user_id, tenant_id, role) VALUES (?, ?, ?)", userId, tenantId, role);
    }

    private ApiSession as(long userId) {
        return ApiSession.login(port, userId);
    }

    private String invitations() {
        return "/api/teams/" + team + "/invitations";
    }

    private String members() {
        return "/api/teams/" + team + "/members";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(HttpResponse<String> res) {
        return json.readValue(res.body(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(HttpResponse<String> res) {
        return json.readValue(res.body(), List.class);
    }

    // ---- 내보내기 ----

    @Test
    void 내보낸_직후_그_사람의_다음_요청은_403() {
        ApiSession bobSession = as(bob);
        assertThat(bobSession.get(members()).statusCode()).isEqualTo(200);

        assertThat(as(alice).delete(members() + "/" + bob).statusCode()).isEqualTo(204);

        assertThat(bobSession.get(members()).statusCode()).as("같은 세션, 다음 요청").isEqualTo(403);
        assertThat(owner.queryForObject("SELECT count(*) FROM membership WHERE user_id = ? AND tenant_id = ?",
                Long.class, bob, team)).isZero();
    }

    @Test
    void 조장_자신은_내보낼_수_없다_400() {
        assertThat(as(alice).delete(members() + "/" + alice).statusCode()).isEqualTo(400);
        assertThat(owner.queryForObject("SELECT count(*) FROM membership WHERE user_id = ?", Long.class, alice))
                .isEqualTo(1L);
    }

    @Test
    void 팀원이_다른_팀원을_내보내면_403() {
        assertThat(as(bob).delete(members() + "/" + carol).statusCode()).isEqualTo(403);
        assertThat(owner.queryForObject("SELECT count(*) FROM membership WHERE user_id = ?", Long.class, carol))
                .isEqualTo(1L);
    }

    @Test
    void 우리_팀_멤버가_아닌_사람을_내보내면_404() {
        assertThat(as(alice).delete(members() + "/" + dave).statusCode()).isEqualTo(404);
        assertThat(owner.queryForObject("SELECT count(*) FROM membership WHERE user_id = ?", Long.class, dave))
                .as("다른 팀 멤버십은 그대로").isEqualTo(1L);
    }

    // ---- 목록 ----

    @Test
    void 팀원_목록은_멤버_누구나_볼_수_있다() {
        HttpResponse<String> res = as(bob).get(members());

        assertThat(res.statusCode()).isEqualTo(200);
        List<Map<String, Object>> rows = list(res);
        assertThat(rows).extracting(r -> r.get("login")).containsExactly("alice", "bob", "carol");
        assertThat(rows.get(0).get("role")).isEqualTo("OWNER");
        assertThat(rows.get(0)).containsKeys("userId", "name", "joinedAt");
    }

    // ---- 초대 링크 ----

    @Test
    void 조장은_팀원_링크를_발급하고_원문은_응답에만() {
        HttpResponse<String> res = as(alice).post(invitations(), "{\"maxUses\":5}");

        assertThat(res.statusCode()).isEqualTo(201);
        Map<String, Object> body = map(res);
        String url = (String) body.get("inviteUrl");
        assertThat(url).startsWith("https://app.example.test/invite/");
        String raw = url.substring(url.lastIndexOf('/') + 1);
        assertThat(((Number) body.get("maxUses")).intValue()).isEqualTo(5);
        assertThat(body).containsKeys("invitationId", "expiresAt").doesNotContainKey("tokenHash");

        Map<String, Object> row = owner.queryForMap("SELECT * FROM invitation");
        assertThat(row.get("role")).isEqualTo("MEMBER");
        assertThat(row.toString()).doesNotContain(raw);

        HttpResponse<String> listed = as(alice).get(invitations());
        assertThat(listed.body()).doesNotContain(raw).doesNotContain("tokenHash").doesNotContain("token_hash");
        assertThat(list(listed)).hasSize(1);
    }

    @Test
    void maxUses가_범위_밖이면_400() {
        ApiSession session = as(alice);
        assertThat(session.post(invitations(), "{\"maxUses\":0}").statusCode()).isEqualTo(400);
        assertThat(session.post(invitations(), "{\"maxUses\":51}").statusCode()).isEqualTo(400);
        assertThat(owner.queryForObject("SELECT count(*) FROM invitation", Long.class)).isZero();
    }

    @Test
    void 팀원은_링크_발급_목록_무효화_모두_403() {
        ApiSession bobSession = as(bob);
        assertThat(bobSession.post(invitations(), "{\"maxUses\":5}").statusCode()).isEqualTo(403);
        assertThat(bobSession.get(invitations()).statusCode()).isEqualTo(403);
        assertThat(bobSession.delete(invitations() + "/1").statusCode()).isEqualTo(403);
    }

    @Test
    void 무효화하면_목록에서_빠지고_수락은_410_기록은_남는다() {
        ApiSession aliceSession = as(alice);
        Map<String, Object> created = map(aliceSession.post(invitations(), "{\"maxUses\":5}"));
        long id = ((Number) created.get("invitationId")).longValue();
        String url = (String) created.get("inviteUrl");
        String raw = url.substring(url.lastIndexOf('/') + 1);

        assertThat(aliceSession.delete(invitations() + "/" + id).statusCode()).isEqualTo(204);

        assertThat(list(aliceSession.get(invitations()))).isEmpty();
        long newcomer = user(9805, "newcomer");
        assertThat(as(newcomer).post("/api/invitations/" + raw + "/accept", "{}").statusCode()).isEqualTo(410);
        assertThat(owner.queryForObject("SELECT count(*) FROM invitation", Long.class)).as("삭제가 아니라 만료")
                .isEqualTo(1L);
    }

    @Test
    void 다른_팀_링크는_무효화할_수_없다_404() {
        long othersInvite = owner.queryForObject("INSERT INTO invitation (tenant_id, token_hash, role, max_uses, "
                + "expires_at) VALUES (?, 'h-other', 'MEMBER', 5, now() + interval '7 days') RETURNING id",
                Long.class, otherTeam);

        assertThat(as(alice).delete(invitations() + "/" + othersInvite).statusCode()).isEqualTo(404);
        assertThat(owner.queryForObject("SELECT expires_at > now() FROM invitation WHERE id = ?", Boolean.class,
                othersInvite)).isTrue();
    }

    @Test
    void 만료된_링크는_목록에_없고_7일_지나면_수락_410() {
        ApiSession aliceSession = as(alice);
        Map<String, Object> created = map(aliceSession.post(invitations(), "{\"maxUses\":5}"));
        String url = (String) created.get("inviteUrl");
        String raw = url.substring(url.lastIndexOf('/') + 1);

        owner.update("UPDATE invitation SET expires_at = now() - interval '1 second'");   // 7일이 지난 상태

        assertThat(list(aliceSession.get(invitations()))).isEmpty();
        long newcomer = user(9806, "late");
        assertThat(as(newcomer).post("/api/invitations/" + raw + "/accept", "{}").statusCode()).isEqualTo(410);
    }

    @Test
    void 팀원_링크로_여러_명이_수락하고_목록에_나온다() {
        Map<String, Object> created = map(as(alice).post(invitations(), "{\"maxUses\":2}"));
        String url = (String) created.get("inviteUrl");
        String raw = url.substring(url.lastIndexOf('/') + 1);
        long n1 = user(9807, "n1");
        long n2 = user(9808, "n2");

        assertThat(as(n1).post("/api/invitations/" + raw + "/accept", "{}").statusCode()).isEqualTo(200);
        assertThat(as(n2).post("/api/invitations/" + raw + "/accept", "{}").statusCode()).isEqualTo(200);

        assertThat(list(as(alice).get(members()))).extracting(r -> r.get("login")).contains("n1", "n2");
        Map<String, Object> invite = list(as(alice).get(invitations())).get(0);
        assertThat(((Number) invite.get("usedCount")).intValue()).isEqualTo(2);
    }
}
