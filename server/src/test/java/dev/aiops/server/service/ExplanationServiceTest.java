package dev.aiops.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.aiops.server.client.LlmClient;
import dev.aiops.server.client.LlmPrompt;
import dev.aiops.server.security.TenantContext;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;

// AI-56 : 새 그룹 설명 저장. 넣기·확인은 owner(RLS 무시)로, 서비스는 진짜 app 계정 + RLS 위에서 돈다
@SpringBootTest
class ExplanationServiceTest extends PostgresContainerSupport {

    @Autowired
    GroupingService groupingService;
    @Autowired
    ExplanationStore store;
    @Autowired
    LlmQuota quota;

    private JdbcTemplate owner;

    /** 호출 횟수를 세고 정해 둔 답을 돌려주는 가짜 LLM */
    static class CountingLlm implements LlmClient {
        final AtomicInteger calls = new AtomicInteger();
        final Optional<String> answer;
        volatile Boolean transactionActiveDuringCall;
        Runnable beforeAnswer;

        CountingLlm(Optional<String> answer) {
            this.answer = answer;
        }

        @Override
        public Optional<String> complete(LlmPrompt prompt) {
            calls.incrementAndGet();
            transactionActiveDuringCall = TransactionSynchronizationManager.isActualTransactionActive();
            if (beforeAnswer != null) {
                beforeAnswer.run();
            }
            return answer;
        }
    }

    @BeforeEach
    void setUp() {
        owner = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), OWNER_USER, OWNER_PASSWORD));
    }

    private Long newTenant() {
        return owner.queryForObject("INSERT INTO tenant (name) VALUES (?) RETURNING id", Long.class,
                "x-" + UUID.randomUUID().toString().substring(0, 8));
    }

    /** 이벤트 1건을 넣고 그룹화까지 돌려 새 그룹을 만든다 → 그룹 id */
    private Long newGroup(Long tenantId) {
        String payload = "{\"time\":1700000000000,\"level\":\"ERROR\","
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

    /** 처리 풀과 같은 조건: 팀을 set 한 상태에서 부른다 */
    private void explainAs(Long tenantId, ExplanationService service, Long groupId) {
        TenantContext.set(tenantId);
        try {
            service.explain(tenantId, groupId);
        } finally {
            TenantContext.clear();
        }
    }

    private String explanationOf(Long groupId) {
        return owner.queryForObject("select ai_explanation from error_group where id = ?", String.class, groupId);
    }

    @Test
    void explainsNewGroupOnce() {
        Long tenantId = newTenant();
        Long groupId = newGroup(tenantId);
        CountingLlm llm = new CountingLlm(Optional.of("원인 후보: 주문이 null"));
        ExplanationService service = new ExplanationService(store, llm, quota);

        explainAs(tenantId, service, groupId);

        assertThat(explanationOf(groupId)).isEqualTo("원인 후보: 주문이 null");
        assertThat(owner.queryForObject("select ai_explained_at is not null from error_group where id = ?",
                Boolean.class, groupId)).isTrue();
        assertThat(llm.calls.get()).isEqualTo(1);

        explainAs(tenantId, service, groupId); // 같은 그룹 다시

        assertThat(llm.calls.get()).as("이미 설명이 있으면 부르지 않는다").isEqualTo(1);
    }

    @Test
    void failureLeavesNull() {
        Long tenantId = newTenant();
        Long groupId = newGroup(tenantId);
        CountingLlm llm = new CountingLlm(Optional.empty());
        ExplanationService service = new ExplanationService(store, llm, quota);

        assertThatCode(() -> explainAs(tenantId, service, groupId)).doesNotThrowAnyException();

        assertThat(explanationOf(groupId)).isNull();
        assertThat(llm.calls.get()).isEqualTo(1);
    }

    @Test
    void noTransactionDuringLlmCall() {
        Long tenantId = newTenant();
        Long groupId = newGroup(tenantId);
        CountingLlm llm = new CountingLlm(Optional.of("설명"));

        explainAs(tenantId, new ExplanationService(store, llm, quota), groupId);

        assertThat(llm.transactionActiveDuringCall).isFalse();
    }

    @Test
    void 다른_팀_그룹은_설명하지_않는다() {
        Long teamA = newTenant();
        Long teamB = newTenant();
        Long groupOfB = newGroup(teamB);
        CountingLlm llm = new CountingLlm(Optional.of("설명"));

        explainAs(teamA, new ExplanationService(store, llm, quota), groupOfB);

        assertThat(llm.calls.get()).isZero();
        assertThat(explanationOf(groupOfB)).isNull();
    }

    @Test
    void 상한에_걸리면_부르지_않는다() {
        Long tenantId = newTenant();
        Long groupId = newGroup(tenantId);
        CountingLlm llm = new CountingLlm(Optional.of("설명"));
        LlmQuota closed = new LlmQuota() {
            @Override
            public boolean tryAcquire(Long tenantId) {
                return false;
            }
        };

        explainAs(tenantId, new ExplanationService(store, llm, closed), groupId);

        assertThat(llm.calls.get()).isZero();
        assertThat(explanationOf(groupId)).isNull();
    }

    @Test
    void 그_사이_다른_쪽이_먼저_채웠으면_덮어쓰지_않는다() {
        Long tenantId = newTenant();
        Long groupId = newGroup(tenantId);
        CountingLlm llm = new CountingLlm(Optional.of("늦게 온 답"));
        // LLM이 답하는 사이에 다른 스레드가 먼저 저장한 상황을 흉내 낸다
        llm.beforeAnswer = () -> owner.update(
                "update error_group set ai_explanation = '먼저 채움', ai_explained_at = now() where id = ?", groupId);

        explainAs(tenantId, new ExplanationService(store, llm, quota), groupId);

        assertThat(explanationOf(groupId)).isEqualTo("먼저 채움");
    }
}