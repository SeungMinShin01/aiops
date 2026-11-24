package dev.aiops.server.security;

import dev.aiops.server.support.PostgresContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 트랜잭션 바인더 (AI-71). 커넥션 풀 크기 1 = 모든 트랜잭션이 같은 DB 연결을 다시 쓴다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.datasource.hikari.maximum-pool-size=1")
class TenantTransactionManagerTest extends PostgresContainerSupport {

    private static final String CURRENT = "SELECT current_setting('app.tenant_id', true)";
    private static final String BACKEND_PID = "SELECT pg_backend_pid()";

    @Autowired
    PlatformTransactionManager txManager;
    @Autowired
    DataSource dataSource;

    private JdbcTemplate jdbc;
    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(txManager);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void 트랜잭션_매니저가_TenantTransactionManager로_교체돼_있다() {
        assertThat(txManager).isInstanceOf(TenantTransactionManager.class);
    }

    @Test
    void 트랜잭션_안에서_current_setting이_팀_번호다() {
        TenantContext.set(42L);

        String inside = tx.execute(s -> jdbc.queryForObject(CURRENT, String.class));

        assertThat(inside).isEqualTo("42");
    }

    @Test
    void 같은_연결로_다음_트랜잭션을_팀_없이_열면_값이_남지_않는다() {
        TenantContext.set(42L);
        Integer firstPid = tx.execute(s -> jdbc.queryForObject(BACKEND_PID, Integer.class));
        TenantContext.clear();

        String[] setting = new String[1];
        Integer secondPid = tx.execute(s -> {
            setting[0] = jdbc.queryForObject(CURRENT, String.class);
            return jdbc.queryForObject(BACKEND_PID, Integer.class);
        });

        assertThat(secondPid).isEqualTo(firstPid); // 정말 같은 DB 연결
        assertThat(setting[0]).isNullOrEmpty(); // set_config(..., true)라 앞 트랜잭션 값이 사라짐
    }

    @Test
    void 읽기_전용_트랜잭션에서도_설정된다() {
        TenantContext.set(42L);
        TransactionTemplate readOnly = new TransactionTemplate(txManager);
        readOnly.setReadOnly(true);

        String inside = readOnly.execute(s -> jdbc.queryForObject(CURRENT, String.class));

        assertThat(inside).isEqualTo("42");
    }

    @Test
    void 트랜잭션_밖에서는_값이_없다() {
        TenantContext.set(42L);
        tx.execute(s -> jdbc.queryForObject(CURRENT, String.class));

        String outside = jdbc.queryForObject(CURRENT, String.class);

        assertThat(outside).isNullOrEmpty();
    }

    @Test
    void 팀이_비어도_트랜잭션은_열린다() {
        TenantContext.clear();

        assertThatCode(() -> tx.execute(s -> jdbc.queryForObject("SELECT 1", Integer.class)))
                .doesNotThrowAnyException();
    }
}
