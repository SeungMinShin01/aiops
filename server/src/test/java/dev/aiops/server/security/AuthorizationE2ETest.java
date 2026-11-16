package dev.aiops.server.security;

import dev.aiops.server.support.PostgresContainerSupport;
import dev.aiops.server.support.TestLogin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 설계자 선행 테스트 (D-080, AI-76). 담당자는 이 파일을 수정하지 않는다 (규칙 9).
 * 권한 판단은 백엔드에서만 한다 (규칙 13). 그래서 HTTP 상태 코드만 본다.
 *
 * - 시드: 팀 A·B, owner(A의 OWNER), member(A의 MEMBER), stranger(소속 없음), admin(관리자
 * 목록, 소속 없음)
 * - 톰캣 스레드 1개: 같은 스레드가 모든 요청을 처리하므로 "요청 뒤 TenantContext가 남는지"를 확인할 수 있다.
 * - 실제 API(13·14·16단계)가 아직 없으므로 TestOnlyController의 _test 경로로 가드·역할만 검사한다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.tomcat.threads.max=1",
        "server.tomcat.threads.min-spare=1",
        "aiops.admin.github-ids=9004",
        "spring.security.oauth2.client.registration.github.client-id=test-client-id",
        "spring.security.oauth2.client.registration.github.client-secret=test-client-secret"
})
class AuthorizationE2ETest extends PostgresContainerSupport {

    @Value("${local.server.port}")
    int port;

    private JdbcTemplate owner;
    private TestLogin login;

    private long teamA;
    private long teamB;
    private long ownerUser;
    private long memberUser;
    private long strangerUser;
    private long adminUser;

    @BeforeEach
    void seed() {
        owner = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), OWNER_USER, OWNER_PASSWORD));
        login = new TestLogin(port);

        owner.execute("TRUNCATE membership, invitation, admin_access_log, app_user, cohort, error_event, error_group, "
                + "app_snapshot, ingest_stat, heartbeat_last, api_key, tenant RESTART IDENTITY CASCADE");

        teamA = owner.queryForObject("INSERT INTO tenant (name) VALUES ('팀 A') RETURNING id", Long.class);
        teamB = owner.queryForObject("INSERT INTO tenant (name) VALUES ('팀 B') RETURNING id", Long.class);

        ownerUser = user(9001, "owner");
        memberUser = user(9002, "member");
        strangerUser = user(9003, "stranger");
        adminUser = user(9004, "admin"); // aiops.admin.github-ids=9004

        owner.update("INSERT INTO membership (user_id, tenant_id, role) VALUES (?, ?, 'OWNER')", ownerUser, teamA);
        owner.update("INSERT INTO membership (user_id, tenant_id, role) VALUES (?, ?, 'MEMBER')", memberUser, teamA);
    }

    private long user(long githubId, String loginName) {
        return owner.queryForObject("INSERT INTO app_user (github_id, login) VALUES (?, ?) RETURNING id",
                Long.class, githubId, loginName);
    }

    private String teamPing(long team) {
        return "/api/teams/" + team + "/_test/ping";
    }

    // ---- 1. 세션 없이 /api → 401 (로그인 페이지 redirect가 아니다) ----

    @Test
    void apiWithoutSessionIs401() {
        HttpResponse<String> res = login.anonymous().get("/api/me");

        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(res.headers().firstValue("location")).isEmpty();
    }

    // ---- 2. 키 없이 /v1 → 401, 세션을 만들지 않는다 ----

    @Test
    void v1WithoutKeyIs401() throws Exception {
        HttpResponse<String> res = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/events"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(res.headers().allValues("set-cookie")).as("/v1은 STATELESS").isEmpty();
    }

    // ---- 3. URL의 팀 번호를 다른 팀으로 → 403 ----

    @Test
    void otherTeamUrlIs403() {
        TestLogin.Session member = login.as(memberUser);

        assertThat(member.get(teamPing(teamB)).statusCode()).as("다른 팀").isEqualTo(403);
        assertThat(member.get("/api/teams/" + teamB + "/no-such-path").statusCode())
                .as("맵핑이 없어도 가드가 먼저 막는다").isEqualTo(403);

        HttpResponse<String> mine = member.get(teamPing(teamA));
        assertThat(mine.statusCode()).as("자기 팀").isEqualTo(200);
        assertThat(mine.body()).as("가드가 URL의 팀으로 TenantContext를 설정").isEqualTo(String.valueOf(teamA));

        assertThat(login.as(strangerUser).get(teamPing(teamA)).statusCode()).as("소속 없는 사람").isEqualTo(403);
    }

    // ---- 4. 팀원이 조장 전용 동작 → 403, 조장은 통과 ----

    @Test
    void memberIssuingKeyIs403() {
        String path = "/api/teams/" + teamA + "/_test/owner-action";

        assertThat(login.as(memberUser).post(path).statusCode()).as("MEMBER").isEqualTo(403);
        assertThat(login.as(ownerUser).post(path).statusCode()).as("OWNER").isEqualTo(201);
    }

    // ---- 5. 관리자 목록에 없는 사람 → /api/admin 403 ----

    @Test
    void nonAdminOnAdminPathIs403() {
        assertThat(login.as(ownerUser).get("/api/admin/_test/ping").statusCode()).as("조장이어도 관리자 아님")
                .isEqualTo(403);
        assertThat(login.as(adminUser).get("/api/admin/_test/ping").statusCode()).as("관리자").isEqualTo(200);
    }

    // ---- 6. 내보내진 직후 같은 세션 → 바로 403 (세션에 소속을 캐시하지 않는다) ----

    @Test
    void removedMemberIs403Immediately() {
        TestLogin.Session member = login.as(memberUser);
        assertThat(member.get(teamPing(teamA)).statusCode()).isEqualTo(200);

        owner.update("DELETE FROM membership WHERE user_id = ? AND tenant_id = ?", memberUser, teamA);

        assertThat(member.get(teamPing(teamA)).statusCode()).isEqualTo(403);
    }

    // ---- 보조. 요청이 끝나면 서버 스레드의 TenantContext가 비어 있다 ----

    @Test
    void tenantContextClearedAfterRequest() {
        TestLogin.Session member = login.as(memberUser);

        assertThat(member.get(teamPing(teamA)).statusCode()).isEqualTo(200); // 가드가 팀 A를 설정
        assertThat(member.get("/api/_test/tenant").body()).as("같은 톰캣 스레드, 다음 요청").isEqualTo("none");

        member.get(teamPing(teamB)); // 403으로 끝난 요청 뒤에도
        assertThat(member.get("/api/_test/tenant").body()).isEqualTo("none");
    }
}