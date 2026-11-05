package dev.aiops.server.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.aiops.server.model.entity.ErrorEventEntity;
import dev.aiops.server.model.entity.TenantEntity;
import dev.aiops.server.model.repository.ErrorEventRepository;
import dev.aiops.server.model.repository.TenantRepository;
import dev.aiops.server.support.PostgresContainerSupport;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

// AI-53 : 처리가 빠진 오래된 이벤트를 run() 이 주워서 진짜 처리기가 끝까지 처리하는지 확인
@SpringBootTest
class ReprocessSchedulerFlowTest extends PostgresContainerSupport {

    private static final String PAYLOAD = "{\"time\":1700000000000,\"level\":\"ERROR\","
            + "\"exceptionType\":\"java.lang.NullPointerException\",\"message\":\"boom\","
            + "\"stack\":[{\"className\":\"dev.shop.OrderService\",\"methodName\":\"place\","
            + "\"fileName\":\"OrderService.java\",\"lineNumber\":42}],"
            + "\"causes\":[],\"thread\":\"main\"}";

    @Autowired ReprocessScheduler scheduler;
    @Autowired ErrorEventRepository events;
    @Autowired TenantRepository tenants;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;

    @Test
    void 처리가_빠진_오래된_이벤트는_run_뒤에_결국_처리된다() {
        Long tenantId = tenants.save(new TenantEntity("f-" + UUID.randomUUID().toString().substring(0, 8))).getId();
        Long id = events.save(new ErrorEventEntity(tenantId, "local", PAYLOAD, "sample", "abc", "abc123def456")).getId();

        // 10분 전에 들어왔는데 아직 처리 안 된 상태 (처리 줄이 유실된 상황)
        tx.executeWithoutResult(s -> em.createNativeQuery(
                        "update error_event set received_at = now() - interval '10 minutes' where id = :id")
                .setParameter("id", id)
                .executeUpdate());

        scheduler.run();

        // 처리는 처리 풀에서 비동기로 돌아서, processed=true 가 될 때까지 최대 10초 기다림
        await().atMost(Duration.ofSeconds(10)).until(() -> isProcessed(id));

        Number groups = (Number) em.createNativeQuery(
                        "select count(*) from error_group where tenant_id = :t")
                .setParameter("t", tenantId)
                .getSingleResult();
        assertThat(groups.longValue()).isEqualTo(1);   // 그룹도 만들어짐
    }

    private boolean isProcessed(Long id) {
        return (Boolean) em.createNativeQuery("select processed from error_event where id = :id")
                .setParameter("id", id)
                .getSingleResult();
    }
}