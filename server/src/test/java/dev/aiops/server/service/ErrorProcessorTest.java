package dev.aiops.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aiops.server.security.TenantContext;
import dev.aiops.server.support.PostgresContainerSupport;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

// AI-52 : 이벤트 묶음을 그룹으로 묶는 흐름 검사. 처리기를 직접 만들어서 동기로 돌림
// AI-16 병합(RLS): 넣기·확인은 owner(RLS 무시)로. 처리기는 진짜 app 계정 + RLS 위에서 돈다 →
// "처리기가 파라미터로 받은 팀을 스스로 set 한다"(AI-73)도 함께 확인된다 (안 하면 0건 처리)
@SpringBootTest
class ErrorProcessorTest extends PostgresContainerSupport {

    @Autowired GroupingService groupingService;

    private JdbcTemplate owner;

    @BeforeEach
    void setUp() {
        owner = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), OWNER_USER, OWNER_PASSWORD));
    }

    // 스택의 줄 번호만 바꿔서 같은 에러를 만듦
    private String payload(int line) {
        return "{\"time\":1700000000000,\"level\":\"ERROR\","
                + "\"exceptionType\":\"java.lang.NullPointerException\",\"message\":\"boom\","
                + "\"stack\":[{\"className\":\"dev.shop.OrderService\",\"methodName\":\"place\","
                + "\"fileName\":\"OrderService.java\",\"lineNumber\":" + line + "}],"
                + "\"causes\":[],\"thread\":\"main\"}";
    }

    private Long newTenant() {
        return owner.queryForObject("INSERT INTO tenant (name) VALUES (?) RETURNING id", Long.class,
                "p-" + UUID.randomUUID().toString().substring(0, 8));
    }

    private Long saveEvent(Long tenantId, String payload) {
        return owner.queryForObject("INSERT INTO error_event (tenant_id, env, payload, app_name, app_commit, host_hash) "
                + "VALUES (?, 'local', CAST(? AS jsonb), 'sample', 'abc', 'abc123def456') RETURNING id",
                Long.class, tenantId, payload);
    }

    private long count(String sql, Long tenantId) {
        return owner.queryForObject(sql, Long.class, tenantId);
    }

    @Test
    void 줄_번호만_다른_스택_2개는_같은_그룹() {
        Long tenantId = newTenant();
        Long a = saveEvent(tenantId, payload(10));
        Long b = saveEvent(tenantId, payload(99));

        new ErrorProcessor(groupingService).process(tenantId, List.of(a, b));

        assertThat(count("select count(*) from error_group where tenant_id = ?", tenantId)).isEqualTo(1);
        assertThat(count("select occurrence_count from error_group where tenant_id = ?", tenantId)).isEqualTo(2);
        assertThat(count("select count(*) from error_event where tenant_id = ? and processed = true", tenantId))
                .isEqualTo(2);
        assertThat(TenantContext.get()).as("처리가 끝나면 비운다").isEmpty();
    }

    @Test
    void 이벤트_3개_중_1개가_깨져_있어도_나머지는_처리된다() {
        Long tenantId = newTenant();
        Long ok1 = saveEvent(tenantId, payload(10));
        Long ok2 = saveEvent(tenantId, payload(20));
        Long broken = saveEvent(tenantId, "[1,2,3]");   // JSON 은 맞지만 ErrorEvent 로는 못 바꾸는 payload

        new ErrorProcessor(groupingService).process(tenantId, List.of(ok1, broken, ok2));

        assertThat(count("select count(*) from error_event where tenant_id = ? and processed = true", tenantId))
                .isEqualTo(2);    // 멀쩡한 2개
        assertThat(count("select count(*) from error_event where tenant_id = ? and processed = false", tenantId))
                .isEqualTo(1);    // 깨진 1개는 false 로 남음
    }

    // AI-73 : 다른 팀 이벤트 id 를 섞어 보내도 RLS 때문에 보이지 않아 처리되지 않는다 (이중 방어)
    @Test
    void 다른_팀_이벤트는_처리되지_않는다() {
        Long teamA = newTenant();
        Long teamB = newTenant();
        Long mine = saveEvent(teamA, payload(10));
        Long theirs = saveEvent(teamB, payload(10));

        new ErrorProcessor(groupingService).process(teamA, List.of(mine, theirs));

        assertThat(count("select count(*) from error_event where tenant_id = ? and processed = true", teamA))
                .isEqualTo(1);
        assertThat(count("select count(*) from error_event where tenant_id = ? and processed = true", teamB))
                .isZero();
        assertThat(count("select count(*) from error_group where tenant_id = ?", teamB)).isZero();
    }
}
