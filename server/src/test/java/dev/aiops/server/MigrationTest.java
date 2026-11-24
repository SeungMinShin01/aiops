package dev.aiops.server;

import dev.aiops.server.support.PostgresContainerSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V3__console.sql (AI-77).
 * - 이 테스트 컨텍스트가 뜬다는 것 자체가 V1~V3 적용 후 ddl-auto=validate 통과 (엔티티와 SQL이 맞음)
 * - "기존 행"은 빈 DB 하나를 새로 만들어 V2까지만 적용 → 행을 넣고 → V3를 적용해서 확인한다
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MigrationTest extends PostgresContainerSupport {

    static final List<String> NEW_TABLES = List.of("app_user", "membership", "invitation", "cohort", "admin_access_log");

    @Autowired
    DataSource dataSource;

    @Test
    void V3_테이블_5개가_생겼다() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);

        assertThat(tables).containsAll(NEW_TABLES);
    }

    @Test
    void 새_테이블에는_RLS가_없다() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        for (String table : NEW_TABLES) {
            Boolean rls = jdbc.queryForObject(
                    "SELECT rowsecurity FROM pg_tables WHERE schemaname = 'public' AND tablename = ?",
                    Boolean.class, table);
            assertThat(rls).as(table).isFalse();
        }
    }

    @Test
    void V3_적용_전에_있던_행은_기본값을_받는다() {
        JdbcTemplate superuser = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), "postgres", "postgres"));
        String db = "aiops_migration_" + System.nanoTime();
        superuser.execute("CREATE DATABASE " + db + " OWNER " + OWNER_USER);
        try {
            String url = jdbcUrl().replaceFirst("/aiops(\\?|$)", "/" + db + "$1");
            JdbcTemplate owner = new JdbcTemplate(new DriverManagerDataSource(url, OWNER_USER, OWNER_PASSWORD));

            // 1) V2까지만
            flyway(url, "2").migrate();
            Long tenantId = owner.queryForObject("INSERT INTO tenant (name) VALUES ('기존 팀') RETURNING id", Long.class);
            owner.update("INSERT INTO api_key (tenant_id, key_hash, env) VALUES (?, ?, 'local')", tenantId, "a".repeat(64));
            owner.update("""
                    INSERT INTO error_group (tenant_id, fingerprint, exception_type, first_env)
                    VALUES (?, ?, 'java.lang.NullPointerException', 'local')
                    """, tenantId, "b".repeat(64)); // owner는 테이블 소유자라 RLS를 무시한다

            // 2) V3 적용
            flyway(url, "latest").migrate();

            Map<String, Object> tenant = owner.queryForMap(
                    "SELECT status, discord_status, cohort_id FROM tenant WHERE id = ?", tenantId);
            assertThat(tenant.get("status")).isEqualTo("ACTIVE");
            assertThat(tenant.get("discord_status")).isEqualTo("NONE");
            assertThat(tenant.get("cohort_id")).isNull();

            Map<String, Object> group = owner.queryForMap("SELECT status, feedback FROM error_group");
            assertThat(group.get("status")).isEqualTo("OPEN");
            assertThat(group.get("feedback")).isNull();

            Map<String, Object> key = owner.queryForMap("SELECT key_prefix, created_by, last_used_at FROM api_key");
            assertThat(key.get("key_prefix")).isNull();
            assertThat(key.get("created_by")).isNull();
            assertThat(key.get("last_used_at")).isNull();
        } finally {
            superuser.execute("DROP DATABASE IF EXISTS " + db + " WITH (FORCE)");
        }
    }

    private static Flyway flyway(String url, String target) {
        return Flyway.configure()
                .dataSource(url, OWNER_USER, OWNER_PASSWORD)
                .locations("classpath:db/migration") // 시드 제외
                .target(target)
                .load();
    }
}
