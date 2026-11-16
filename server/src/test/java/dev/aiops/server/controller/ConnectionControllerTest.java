package dev.aiops.server.controller;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aiops.server.support.ApiSession;
import dev.aiops.server.support.PostgresContainerSupport;
import java.net.http.HttpResponse;
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
 * 연결 확인 API (AI-98). 진짜 서버 + 테스트 세션 + RLS.
 * heartbeat_last는 owner 계정으로 넣고 (now() - interval), API는 app 계정 + RLS로 읽는다.
 * 팀 A 조장 alice, 팀 B 조장 dave.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConnectionControllerTest extends PostgresContainerSupport {

    @Value("${local.server.port}")
    int port;

    private final JsonMapper json = JsonMapper.builder().build();
    private JdbcTemplate owner;
    private long team;
    private long otherTeam;
    private long alice;
    private long dave;

    @BeforeEach
    void seed() {
        owner = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), OWNER_USER, OWNER_PASSWORD));
        owner.execute("TRUNCATE membership, invitation, admin_access_log, app_user, cohort, error_event, error_group, "
                + "app_snapshot, ingest_stat, heartbeat_last, api_key, tenant RESTART IDENTITY CASCADE");
        team = owner.queryForObject("INSERT INTO tenant (name) VALUES ('팀 A') RETURNING id", Long.class);
        otherTeam = owner.queryForObject("INSERT INTO tenant (name) VALUES ('팀 B') RETURNING id", Long.class);
        alice = owner.queryForObject("INSERT INTO app_user (github_id, login) VALUES (9901, 'alice') RETURNING id", Long.class);
        dave = owner.queryForObject("INSERT INTO app_user (github_id, login) VALUES (9904, 'dave') RETURNING id", Long.class);
        owner.update("INSERT INTO membership (user_id, tenant_id, role) VALUES (?, ?, 'OWNER')", alice, team);
        owner.update("INSERT INTO membership (user_id, tenant_id, role) VALUES (?, ?, 'OWNER')", dave, otherTeam);
    }

    private void signal(long tenantId, String env, String hostHash, String ago) {
        owner.update("INSERT INTO heartbeat_last (tenant_id, env, host_hash, last_seen_at) "
                + "VALUES (?, ?, ?, now() - CAST(? AS interval))", tenantId, env, hostHash, ago);
    }

    private HttpResponse<String> get(long userId, long teamId) {
        return ApiSession.login(port, userId).get("/api/teams/" + teamId + "/connection");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> env(HttpResponse<String> res, String env) {
        return (Map<String, Object>) json.readValue(res.body(), Map.class).get(env);
    }

    @Test
    void 신호가_없으면_둘_다_NEVER() {
        HttpResponse<String> res = get(alice, team);

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(env(res, "local")).containsEntry("state", "NEVER").containsEntry("hostCount", 0);
        assertThat(env(res, "local").get("lastSeenAt")).isNull();
        assertThat(env(res, "prod")).containsEntry("state", "NEVER");
    }

    @Test
    void 최근_신호는_CONNECTED_오래된_신호는_NOT_CONNECTED() {
        signal(team, "local", "aaaaaaaaaaaa", "30 seconds");
        signal(team, "prod", "bbbbbbbbbbbb", "10 minutes");

        HttpResponse<String> res = get(alice, team);

        assertThat(env(res, "local")).containsEntry("state", "CONNECTED").containsEntry("hostCount", 1);
        assertThat(env(res, "local").get("lastSeenAt")).isNotNull();
        assertThat(env(res, "prod")).containsEntry("state", "NOT_CONNECTED").containsEntry("hostCount", 0);
    }

    @Test
    void 호스트_2개_중_1개만_최근이면_hostCount_1() {
        signal(team, "local", "aaaaaaaaaaaa", "20 seconds");
        signal(team, "local", "cccccccccccc", "20 minutes");

        assertThat(env(get(alice, team), "local")).containsEntry("state", "CONNECTED").containsEntry("hostCount", 1);
    }

    @Test
    void 다른_팀_신호는_반영되지_않는다() {
        signal(otherTeam, "local", "dddddddddddd", "5 seconds");

        assertThat(env(get(alice, team), "local")).containsEntry("state", "NEVER");
        assertThat(env(get(dave, otherTeam), "local")).containsEntry("state", "CONNECTED");
    }

    @Test
    void 팀원이_아니면_403() {
        signal(team, "local", "aaaaaaaaaaaa", "5 seconds");

        assertThat(get(dave, team).statusCode()).isEqualTo(403);
    }

    @Test
    void 캐시_금지_헤더와_host_hash_미노출() {
        signal(team, "local", "aaaaaaaaaaaa", "5 seconds");

        HttpResponse<String> res = get(alice, team);

        assertThat(res.headers().firstValue("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("no-store"));
        assertThat(res.body()).doesNotContain("aaaaaaaaaaaa");
    }
}
