package dev.aiops.server.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.aiops.server.support.PostgresContainerSupport;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

// AI-53 : 처리가 빠진 오래된 이벤트를 run() 이 주워서 진짜 처리기가 끝까지 처리하는지 확인
// AI-16 병합(RLS): 넣기·확인은 owner(RLS 무시)로. 처리 경로는 진짜 app 계정 + RLS 그대로 →
// 스케줄러(팀별 조회)와 처리 스레드(팀 set)가 RLS 위에서 실제로 동작하는지까지 확인된다
@SpringBootTest
class ReprocessSchedulerFlowTest extends PostgresContainerSupport {

    private static final String PAYLOAD = "{\"time\":1700000000000,\"level\":\"ERROR\","
            + "\"exceptionType\":\"java.lang.NullPointerException\",\"message\":\"boom\","
            + "\"stack\":[{\"className\":\"dev.shop.OrderService\",\"methodName\":\"place\","
            + "\"fileName\":\"OrderService.java\",\"lineNumber\":42}],"
            + "\"causes\":[],\"thread\":\"main\"}";

    @Autowired ReprocessScheduler scheduler;

    @Test
    void 처리가_빠진_오래된_이벤트는_run_뒤에_결국_처리된다() {
        JdbcTemplate owner = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), OWNER_USER, OWNER_PASSWORD));
        Long tenantId = owner.queryForObject("INSERT INTO tenant (name) VALUES (?) RETURNING id", Long.class,
                "f-" + UUID.randomUUID().toString().substring(0, 8));

        // 10분 전에 들어왔는데 아직 처리 안 된 상태 (처리 줄이 유실된 상황)
        Long id = owner.queryForObject("INSERT INTO error_event (tenant_id, env, payload, app_name, app_commit, "
                        + "host_hash, received_at) VALUES (?, 'local', CAST(? AS jsonb), 'sample', 'abc', "
                        + "'abc123def456', now() - interval '10 minutes') RETURNING id",
                Long.class, tenantId, PAYLOAD);

        scheduler.run();

        // 처리는 처리 풀에서 비동기로 돌아서, processed=true 가 될 때까지 최대 10초 기다림
        await().atMost(Duration.ofSeconds(10)).until(() -> Boolean.TRUE.equals(
                owner.queryForObject("select processed from error_event where id = ?", Boolean.class, id)));

        Long groups = owner.queryForObject("select count(*) from error_group where tenant_id = ?", Long.class,
                tenantId);
        assertThat(groups).isEqualTo(1L);   // 그룹도 만들어짐
    }
}
