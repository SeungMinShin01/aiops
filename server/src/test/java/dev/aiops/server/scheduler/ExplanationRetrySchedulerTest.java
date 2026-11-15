package dev.aiops.server.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aiops.server.client.LlmClient;
import dev.aiops.server.config.LlmProperties;
import dev.aiops.server.model.repository.ErrorGroupRepository;
import dev.aiops.server.model.repository.TenantRepository;
import dev.aiops.server.security.TenantContext;
import dev.aiops.server.service.ErrorProcessor;
import dev.aiops.server.service.ExplanationService;
import dev.aiops.server.service.ExplanationStore;
import dev.aiops.server.service.GroupingService;
import dev.aiops.server.service.LlmQuota;
import dev.aiops.server.support.PostgresContainerSupport;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;

// AI-57 : 설명이 없는 그룹 재시도. run() 을 직접 부른다 (주기를 기다리지 않음)
// 스케줄러가 모든 팀을 돌기 때문에 시작할 때 그룹·이벤트를 비운다 (ReprocessSchedulerTest와 같은 방식)
@SpringBootTest
class ExplanationRetrySchedulerTest extends PostgresContainerSupport {

    // 가짜 값 (진짜 키 아님, 규칙 10)
    static final LlmProperties KEY_SET = new LlmProperties("fake-llm-key", "https://llm.test/v1", "m", null);
    static final LlmProperties KEY_EMPTY = new LlmProperties("", "https://llm.test/v1", "m", null);

    @Autowired
    GroupingService groupingService;
    @Autowired
    ExplanationStore store;
    @Autowired
    LlmQuota quota;
    @Autowired
    ErrorGroupRepository groups;
    @Autowired
    TenantRepository tenants;
    @Autowired
    PlatformTransactionManager transactionManager;

    private JdbcTemplate owner;
    private final AtomicInteger llmCalls = new AtomicInteger();
    private final LlmClient llm = prompt -> {
        llmCalls.incrementAndGet();
        return Optional.of("설명");
    };

    @BeforeEach
    void clean() {
        owner = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), OWNER_USER, OWNER_PASSWORD));
        owner.execute("TRUNCATE error_event, error_group RESTART IDENTITY CASCADE");
        llmCalls.set(0);
    }

    private ExplanationRetryScheduler scheduler(LlmProperties properties, LlmQuota quotaToUse) {
        ExplanationService service = new ExplanationService(store, llm, quotaToUse);
        return new ExplanationRetryScheduler(groups, tenants, service, transactionManager, properties, "PT24H", 50);
    }

    private Long newTenant() {
        return owner.queryForObject("INSERT INTO tenant (name) VALUES (?) RETURNING id", Long.class,
                "y-" + UUID.randomUUID().toString().substring(0, 8));
    }

    /** 이벤트 1건을 넣고 그룹화까지 돌려 새 그룹을 만든다 → 그룹 id (설명은 NULL) */
    private Long newGroup(Long tenantId) {
        String payload = "{\"time\":" + System.currentTimeMillis() + ",\"level\":\"ERROR\","
                + "\"exceptionType\":\"java.lang.NullPointerException\",\"message\":\"boom\","
                + "\"stack\":[{\"className\":\"dev.shop.OrderService\",\"methodName\":\"place\","
                + "\"fileName\":\"OrderService.java\",\"lineNumber\":10}],"
                + "\"causes\":[],\"thread\":\"main\",\"phase\":\"RUNTIME\"}";
        Long eventId = owner.queryForObject(
                "INSERT INTO error_event (tenant_id, env, payload, app_name, app_commit, host_hash) "
                        + "VALUES (?, 'local', CAST(? AS jsonb), 'sample', 'abc', 'abc123def456') RETURNING id",
                Long.class, tenantId, payload);
        new ErrorProcessor(groupingService).process(tenantId, List.of(eventId));
        return owner.queryForObject("select id from error_group where tenant_id = ?", Long.class, tenantId);
    }

    private String explanationOf(Long groupId) {
        return owner.queryForObject("select ai_explanation from error_group where id = ?", String.class, groupId);
    }

    @Test
    void retriesMissingExplanation() {
        Long recent = newGroup(newTenant());
        Long old = newGroup(newTenant());
        owner.update("update error_group set first_seen_at = now() - interval '25 hours' where id = ?", old);

        scheduler(KEY_SET, quota).run();

        assertThat(llmCalls.get()).as("24시간 안의 그룹만").isEqualTo(1);
        assertThat(explanationOf(recent)).isEqualTo("설명");
        assertThat(explanationOf(old)).isNull();
    }

    @Test
    void 상한에_걸리면_첫_거절에서_멈춘다() {
        newGroup(newTenant());
        newGroup(newTenant());
        newGroup(newTenant());
        AtomicInteger asked = new AtomicInteger();
        LlmQuota onlyOnce = new LlmQuota(null, 0) {
            @Override
            public boolean tryAcquire(Long tenantId) {
                return asked.incrementAndGet() == 1; // 첫 번째만 허용
            }
        };

        scheduler(KEY_SET, onlyOnce).run();

        assertThat(llmCalls.get()).isEqualTo(1);
        assertThat(asked.get()).as("두 번째에서 거절당하고 세 번째는 묻지도 않는다").isEqualTo(2);
    }

    @Test
    void LLM_키가_없으면_재시도하지_않는다() {
        Long groupId = newGroup(newTenant());

        scheduler(KEY_EMPTY, quota).run();

        assertThat(llmCalls.get()).isZero();
        assertThat(explanationOf(groupId)).isNull();
    }

    @Test
    void 정지된_팀은_건너뛴다() {
        Long tenantId = newTenant();
        Long groupId = newGroup(tenantId);
        owner.update("update tenant set status = 'SUSPENDED' where id = ?", tenantId);

        scheduler(KEY_SET, quota).run();

        assertThat(llmCalls.get()).isZero();
        assertThat(explanationOf(groupId)).isNull();
    }

    @Test
    void 끝나면_TenantContext가_비어_있다() {
        newGroup(newTenant());

        scheduler(KEY_SET, quota).run();

        assertThat(TenantContext.get()).isEmpty();
    }
}