package dev.aiops.server.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * 진짜 Postgres 16을 띄우고, 로컬과 같은 01-roles.sh로 계정 3개를 만든다.
 * 서버는 app 계정, Flyway는 owner 계정으로 붙는다 (로컬·운영과 같은 구조).
 * 
 * @ServiceConnection을 쓰지 않는 이유: 슈퍼유저로 붙게 되어 RLS 테스트(11단계)가 무의미해진다.
 */
public abstract class PostgresContainerSupport {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16")
            .withDatabaseName("aiops") // 01-roles.sh가 'aiops'를 직접 쓴다
            .withUsername("postgres")
            .withPassword("postgres")
            .withEnv("AIOPS_OWNER_PASSWORD", "owner_test")
            .withEnv("AIOPS_APP_PASSWORD", "app_test")
            .withEnv("GRAFANA_RO_PASSWORD", "grafana_test")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("../infra/local/init/01-roles.sh", 0755),
                    "/docker-entrypoint-initdb.d/01-roles.sh");

    static {
        POSTGRES.start(); // 모든 테스트 클래스가 컨테이너 하나를 같이 쓴다
    }

    // ---- 격리 테스트(AI-69)용: 시드·검증은 owner로 (owner는 테이블 소유자라 RLS를 무시한다) ----
    public static final String OWNER_USER = "aiops_owner";
    public static final String OWNER_PASSWORD = "owner_test";

    public static String jdbcUrl() {
        return POSTGRES.getJdbcUrl();
    }

    @DynamicPropertySource
    static void dbProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "aiops_app");
        registry.add("spring.datasource.password", () -> "app_test");
        registry.add("spring.flyway.user", () -> "aiops_owner");
        registry.add("spring.flyway.password", () -> "owner_test");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration"); // 시드 제외
    }
}