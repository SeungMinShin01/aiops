package dev.aiops.server.scheduler;

import dev.aiops.server.config.LlmProperties;
import dev.aiops.server.model.entity.TenantEntity;
import dev.aiops.server.model.entity.TenantStatus;
import dev.aiops.server.model.repository.ErrorGroupRepository;
import dev.aiops.server.model.repository.TenantRepository;
import dev.aiops.server.security.TenantContext;
import dev.aiops.server.service.ExplanationService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 설명이 없는(ai_explanation NULL) 최근 그룹을 주기적으로 다시 시도한다 (AI-57).
 * 상한·LLM 장애로 설명을 못 만든 그룹이 대상이다. 새 경로를 만들지 않고 같은 ExplanationService.explain을 탄다.
 * <ul>
 * <li>ReprocessScheduler와 따로 둔 이유: 그쪽은 짧게 끝나야 하는데 LLM 호출은 건당 최대 20초다</li>
 * <li>LLM 키가 없으면 아무것도 하지 않는다 (가짜 응답으로 그룹을 채우지 않는다)</li>
 * <li>retry-window(기본 24시간)보다 오래된 그룹은 포기한다 (콘솔에는 설명 없음으로 보인다)</li>
 * <li>상한에 걸리면 첫 거절에서 멈춘다 (나머지를 헛돌지 않게)</li>
 * <li>RLS를 우회하지 않는다. 팀 목록을 돌며 팀마다 set → 조회·설명 → clear (D-082)</li>
 * </ul>
 */
@Component
public class ExplanationRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExplanationRetryScheduler.class);

    private final ErrorGroupRepository groups;
    private final TenantRepository tenants;
    private final ExplanationService explanationService;
    private final TransactionTemplate readTx; // 조회를 트랜잭션 안에서 → 바인더가 app.tenant_id 를 넣는다
    private final LlmProperties properties;
    private final Duration window;
    private final int batch;

    public ExplanationRetryScheduler(ErrorGroupRepository groups,
            TenantRepository tenants,
            ExplanationService explanationService,
            PlatformTransactionManager transactionManager,
            LlmProperties properties,
            @Value("${aiops.llm.retry-window:PT24H}") String window,
            @Value("${aiops.llm.retry-batch:50}") int batch) {
        this.groups = groups;
        this.tenants = tenants;
        this.explanationService = explanationService;
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
        this.properties = properties;
        this.window = Duration.parse(window);
        this.batch = batch;
    }

    @Scheduled(fixedDelayString = "${aiops.llm.retry-interval:PT5M}", initialDelayString = "${aiops.llm.retry-initial-delay:PT2M}")
    public void run() {
        if (!properties.isConfigured()) {
            return; // 키 없음: 재시도할 이유가 없다
        }
        try {
            Instant since = Instant.now().minus(window);
            int remaining = batch;
            int saved = 0;

            for (TenantEntity tenant : tenants.findAll()) { // tenant 는 RLS 없음 (D-023)
                if (remaining <= 0) {
                    break; // 이번 주기 상한. 남은 것은 다음 주기에
                }
                if (tenant.getStatus() == TenantStatus.SUSPENDED) {
                    continue; // 정지된 팀에는 LLM을 쓰지 않는다
                }
                Long tenantId = tenant.getId();
                int limit = remaining;
                TenantContext.set(tenantId);
                try {
                    List<Long> groupIds = readTx.execute(status -> groups.findIdsMissingExplanation(since, limit));
                    if (groupIds == null) {
                        continue;
                    }
                    for (Long groupId : groupIds) {
                        remaining--;
                        ExplanationService.Outcome outcome = explanationService.explain(tenantId, groupId);
                        if (outcome == ExplanationService.Outcome.SAVED) {
                            saved++;
                        }
                        if (outcome == ExplanationService.Outcome.QUOTA_EXCEEDED) {
                            log.info("설명 재시도 중단: 일일 상한 (이번 주기 저장 {}건)", saved);
                            return;
                        }
                    }
                } catch (RuntimeException e) {
                    // 한 팀이 실패해도 다음 팀은 계속
                    log.error("설명 재시도 실패 tenantId={} {}", tenantId, e.getClass().getSimpleName());
                } finally {
                    TenantContext.clear();
                }
            }
            if (saved > 0) {
                log.info("설명 재시도 저장 {}건", saved);
            }
        } catch (RuntimeException e) {
            // 예외가 나도 스케줄이 죽지 않게 로그만 (다음 주기에 다시 실행됨)
            log.error("설명 재시도 스케줄 실패 {}", e.getClass().getSimpleName());
        }
    }
}