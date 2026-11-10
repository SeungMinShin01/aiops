package dev.aiops.server.security;

import dev.aiops.server.support.PostgresContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V2__rls.sql (AI-72) 정책이 실제로 걸렸는지 카탈로그로 확인한다.
 * 나중에 팀 테이블(tenant_id 컬럼)을 추가하고 정책을 빠뜨리면 마지막 테스트에서 걸린다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RlsPolicyTest extends PostgresContainerSupport {

    static final Set<String> TEAM_TABLES =
            Set.of("error_event", "error_group", "app_snapshot", "ingest_stat", "heartbeat_last");

    /**
     * tenant_id가 있지만 RLS를 걸지 않는 테이블.
     * api_key·membership·invitation은 팀을 알아내기 전에 조회해야 하고, admin_access_log는 관리자 전용이다 (D-023).
     */
    static final Set<String> EXCEPTIONS = Set.of("api_key", "membership", "invitation", "admin_access_log");

    @Autowired
    DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
    }

    private List<Map<String, Object>> policies() {
        return jdbc.queryForList("""
                SELECT tablename, qual, with_check, array_to_string(roles, ',') AS roles
                FROM pg_policies
                WHERE schemaname = 'public' AND policyname = 'tenant_isolation'
                """);
    }

    @Test
    void 팀_테이블_5개_전부에_tenant_isolation_정책이_있다() {
        Set<String> withPolicy = new HashSet<>();
        policies().forEach(p -> withPolicy.add((String) p.get("tablename")));

        assertThat(withPolicy).containsExactlyInAnyOrderElementsOf(TEAM_TABLES);
    }

    @Test
    void 정책마다_USING과_WITH_CHECK가_둘_다_있고_역할을_제한하지_않는다() {
        assertThat(policies()).hasSize(TEAM_TABLES.size()).allSatisfy(p -> {
            assertThat(p.get("qual")).as("USING " + p.get("tablename")).isNotNull();
            assertThat(p.get("with_check")).as("WITH CHECK " + p.get("tablename")).isNotNull();
            assertThat(p.get("roles")).as("roles " + p.get("tablename")).isEqualTo("public");
        });
    }

    @Test
    void 팀_테이블은_RLS가_켜져_있다() {
        for (String table : TEAM_TABLES) {
            Boolean on = jdbc.queryForObject(
                    "SELECT rowsecurity FROM pg_tables WHERE schemaname = 'public' AND tablename = ?",
                    Boolean.class, table);
            assertThat(on).as(table).isTrue();
        }
    }

    @Test
    void tenant_id_컬럼이_있는_테이블은_예외_목록_빼고_전부_정책이_있다() {
        Set<String> tenantTables = new HashSet<>(jdbc.queryForList("""
                SELECT table_name FROM information_schema.columns
                WHERE table_schema = 'public' AND column_name = 'tenant_id'
                """, String.class));
        tenantTables.removeAll(EXCEPTIONS);

        Set<String> withPolicy = new HashSet<>();
        policies().forEach(p -> withPolicy.add((String) p.get("tablename")));

        assertThat(withPolicy).containsAll(tenantTables);
    }
}
