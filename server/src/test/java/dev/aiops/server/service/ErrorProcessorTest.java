package dev.aiops.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aiops.server.model.entity.ErrorEventEntity;
import dev.aiops.server.model.entity.TenantEntity;
import dev.aiops.server.model.repository.ErrorEventRepository;
import dev.aiops.server.model.repository.TenantRepository;
import dev.aiops.server.support.PostgresContainerSupport;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

// AI-52 : 이벤트 묶음을 그룹으로 묶는 흐름 검사. 처리기를 직접 만들어서 동기로 돌림
@SpringBootTest
class ErrorProcessorTest extends PostgresContainerSupport {

    @Autowired GroupingService groupingService;
    @Autowired ErrorEventRepository events;
    @Autowired TenantRepository tenants;
    @Autowired EntityManager em;

    // 스택의 줄 번호만 바꿔서 같은 에러를 만듦
    private String payload(int line) {
        return "{\"time\":1700000000000,\"level\":\"ERROR\","
                + "\"exceptionType\":\"java.lang.NullPointerException\",\"message\":\"boom\","
                + "\"stack\":[{\"className\":\"dev.shop.OrderService\",\"methodName\":\"place\","
                + "\"fileName\":\"OrderService.java\",\"lineNumber\":" + line + "}],"
                + "\"causes\":[],\"thread\":\"main\"}";
    }

    private Long newTenant() {
        return tenants.save(new TenantEntity("p-" + UUID.randomUUID().toString().substring(0, 8))).getId();
    }

    private Long saveEvent(Long tenantId, String payload) {
        return events.save(new ErrorEventEntity(tenantId, "local", payload, "sample", "abc", "abc123def456")).getId();
    }

    private long count(String sql, Long tenantId) {
        return ((Number) em.createNativeQuery(sql).setParameter("t", tenantId).getSingleResult()).longValue();
    }

    @Test
    void 줄_번호만_다른_스택_2개는_같은_그룹() {
        Long tenantId = newTenant();
        Long a = saveEvent(tenantId, payload(10));
        Long b = saveEvent(tenantId, payload(99));

        new ErrorProcessor(groupingService).process(tenantId, List.of(a, b));

        assertThat(count("select count(*) from error_group where tenant_id = :t", tenantId)).isEqualTo(1);
        assertThat(count("select occurrence_count from error_group where tenant_id = :t", tenantId)).isEqualTo(2);
        assertThat(count("select count(*) from error_event where tenant_id = :t and processed = true", tenantId))
                .isEqualTo(2);
    }

    @Test
    void 이벤트_3개_중_1개가_깨져_있어도_나머지는_처리된다() {
        Long tenantId = newTenant();
        Long ok1 = saveEvent(tenantId, payload(10));
        Long ok2 = saveEvent(tenantId, payload(20));
        Long broken = saveEvent(tenantId, "[1,2,3]");   // JSON 은 맞지만 ErrorEvent 로는 못 바꾸는 payload

        new ErrorProcessor(groupingService).process(tenantId, List.of(ok1, broken, ok2));

        assertThat(count("select count(*) from error_event where tenant_id = :t and processed = true", tenantId))
                .isEqualTo(2);    // 멀쩡한 2개
        assertThat(count("select count(*) from error_event where tenant_id = :t and processed = false", tenantId))
                .isEqualTo(1);    // 깨진 1개는 false 로 남음
    }
}