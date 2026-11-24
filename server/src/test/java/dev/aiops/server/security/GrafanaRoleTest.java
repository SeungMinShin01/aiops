package dev.aiops.server.security;

import dev.aiops.server.support.PostgresContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Grafana 계정 (AI-75). grafana_ro = RLS 우회 + 읽기 전용 + 민감 컬럼 제외.
 * Spring 컨텍스트는 Flyway(V1·V2)를 적용하려고 띄운다. 검사는 grafana_ro로 직접 붙어서 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class GrafanaRoleTest extends PostgresContainerSupport {

    /** PostgresContainerSupport가 컨테이너에 넣는 GRAFANA_RO_PASSWORD 값 (테스트 전용) */
    private static final String GRAFANA_PASSWORD = "grafana_test";

    /** Postgres insufficient_privilege. Spring은 이걸 "bad SQL grammar"로 감싸므로 메시지 대신 코드로 본다 */
    private static final String PERMISSION_DENIED = "42501";

    private JdbcTemplate owner;
    private JdbcTemplate grafana;

    @BeforeEach
    void seed() {
        owner = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), OWNER_USER, OWNER_PASSWORD));
        grafana = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), "grafana_ro", GRAFANA_PASSWORD));

        // owner는 테이블 소유자라 RLS를 무시하고 넣을 수 있다. 팀 2개에 이벤트 1건씩
        for (String name : new String[] {"그라파나 팀 A", "그라파나 팀 B"}) {
            Long team = owner.queryForObject("INSERT INTO tenant (name) VALUES (?) RETURNING id", Long.class, name);
            owner.update("INSERT INTO error_event (tenant_id, env, payload) VALUES (?, 'local', '{}'::jsonb)", team);
        }
    }

    @Test
    void grafana_ro는_팀_설정_없이도_전체_팀을_본다() {
        Long teams = grafana.queryForObject("SELECT count(DISTINCT tenant_id) FROM error_event", Long.class);

        assertThat(teams).isGreaterThanOrEqualTo(2L); // RLS였다면 팀 미설정이라 0
    }

    @Test
    void grafana_ro는_쓰기를_못_한다() {
        Long anyTeam = owner.queryForObject("SELECT min(id) FROM tenant", Long.class);

        assertPermissionDenied(() -> grafana.update(
                "INSERT INTO error_event (tenant_id, env, payload) VALUES (?, 'local', '{}'::jsonb)", anyTeam));
        assertPermissionDenied(() -> grafana.update("DELETE FROM error_event"));
    }

    @Test
    void grafana_ro는_api_key_key_hash를_못_읽는다() {
        assertPermissionDenied(() -> grafana.queryForList("SELECT key_hash FROM api_key"));
        // 민감하지 않은 컬럼은 읽힌다
        grafana.queryForList("SELECT id, tenant_id, env, revoked_at, created_at FROM api_key");
    }

    @Test
    void grafana_ro는_tenant_웹훅_주소를_못_읽는다() {
        assertPermissionDenied(() -> grafana.queryForList("SELECT discord_webhook_local FROM tenant"));
        assertPermissionDenied(() -> grafana.queryForList("SELECT discord_webhook_prod FROM tenant"));
        assertThat(grafana.queryForList("SELECT id, name FROM tenant")).isNotEmpty();
    }

    /** 권한 부족(42501)으로 실패해야 한다. 오타 같은 다른 SQL 에러로 통과하지 않게 코드까지 확인 */
    private static void assertPermissionDenied(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(DataAccessException.class, e -> {
                    assertThat(e.getMostSpecificCause()).isInstanceOf(SQLException.class);
                    assertThat(((SQLException) e.getMostSpecificCause()).getSQLState()).isEqualTo(PERMISSION_DENIED);
                });
    }

    @Test
    void BYPASSRLS는_grafana_ro만_있고_서버_계정은_없다() {
        Boolean grafanaBypass = owner.queryForObject(
                "SELECT rolbypassrls FROM pg_roles WHERE rolname = 'grafana_ro'", Boolean.class);
        Boolean appBypass = owner.queryForObject(
                "SELECT rolbypassrls FROM pg_roles WHERE rolname = 'aiops_app'", Boolean.class);
        Boolean ownerBypass = owner.queryForObject(
                "SELECT rolbypassrls FROM pg_roles WHERE rolname = 'aiops_owner'", Boolean.class);

        assertThat(grafanaBypass).isTrue();
        assertThat(appBypass).isFalse();
        assertThat(ownerBypass).isFalse();
    }

    @Test
    void grafana_ro는_DB나_계정을_만들_수_없다() {
        Boolean createDb = owner.queryForObject("SELECT rolcreatedb FROM pg_roles WHERE rolname = 'grafana_ro'", Boolean.class);
        Boolean createRole = owner.queryForObject("SELECT rolcreaterole FROM pg_roles WHERE rolname = 'grafana_ro'", Boolean.class);

        assertThat(createDb).isFalse();
        assertThat(createRole).isFalse();
    }
}
