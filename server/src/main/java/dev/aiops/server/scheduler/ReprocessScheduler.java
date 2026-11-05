package dev.aiops.server.scheduler;

import dev.aiops.server.model.entity.TenantEntity;
import dev.aiops.server.model.repository.ErrorEventRepository;
import dev.aiops.server.model.repository.TenantRepository;
import dev.aiops.server.security.TenantContext;
import dev.aiops.server.service.ErrorProcessor;
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

// AI-53 : 처리 안 된(processed=false) 이벤트를 주기적으로 주워서 다시 처리함
// AI-73 : RLS(11단계) 이후에는 팀 설정 없이 error_event 를 읽으면 0건이다.
//         서버 계정에 RLS 우회를 주지 않고, 팀 목록을 돌며 팀마다 set → 조회 → clear (D-082)
@Component
public class ReprocessScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReprocessScheduler.class);

    private final ErrorEventRepository errorEventRepository;
    private final TenantRepository tenantRepository;
    private final ErrorProcessor errorProcessor;
    private final TransactionTemplate readTx;   // 조회를 트랜잭션 안에서 → 바인더가 app.tenant_id 를 넣는다
    private final Duration minAge;   // 이 시간보다 오래된 것만 줍는 유예 (방금 들어와 처리 풀에 있는 것과 안 겹치게)
    private final int batch;         // 한 번에 줍는 최대 개수 (전체 팀 합계)

    // 값은 전부 yml (코드에 박지 않음)
    public ReprocessScheduler(ErrorEventRepository errorEventRepository,
                              TenantRepository tenantRepository,
                              ErrorProcessor errorProcessor,
                              PlatformTransactionManager transactionManager,
                              @Value("${aiops.reprocess.min-age:PT2M}") String minAge,
                              @Value("${aiops.reprocess.batch:500}") int batch) {
        this.errorEventRepository = errorEventRepository;
        this.tenantRepository = tenantRepository;
        this.errorProcessor = errorProcessor;
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
        this.minAge = Duration.parse(minAge);
        this.batch = batch;
    }

    // AI-53 : 이전 실행이 끝난 뒤 interval 만큼 쉬고 다시 실행. 이 메서드는 짧게 끝남 (처리 자체는 처리 풀이 함)
    @Scheduled(fixedDelayString = "${aiops.reprocess.interval:PT5M}",
               initialDelayString = "${aiops.reprocess.initial-delay:PT1M}")
    public void run() {
        try {
            Instant cutoff = Instant.now().minus(minAge);
            int total = 0;
            int teams = 0;

            for (TenantEntity tenant : tenantRepository.findAll()) {   // tenant 는 RLS 없음 (D-023)
                if (total >= batch) {
                    break;   // 이번 주기 상한. 남은 것은 다음 주기에
                }
                Long tenantId = tenant.getId();
                try {
                    List<Long> eventIds = unprocessedOf(tenantId, cutoff, batch - total);
                    if (eventIds.isEmpty()) {
                        continue;
                    }
                    // 팀마다 1번씩 같은 process 를 부름 (@Async 라서 바로 돌아옴. 처리 스레드가 팀을 다시 set 한다)
                    errorProcessor.process(tenantId, eventIds);
                    total += eventIds.size();
                    teams++;
                } catch (RuntimeException e) {
                    // 한 팀이 실패해도 다음 팀은 계속
                    log.error("재처리 요청 실패 tenantId={}", tenantId, e);
                }
            }
            if (total > 0) {
                log.info("재처리 요청 이벤트 {}개 팀 {}개", total, teams);
            }
        } catch (RuntimeException e) {
            // 예외가 나도 스케줄이 죽지 않게 로그만 (다음 주기에 다시 실행됨)
            log.error("재처리 스케줄 실패", e);
        }
    }

    // 이 팀의 미처리 이벤트 id (set 한 곳이 finally clear)
    private List<Long> unprocessedOf(Long tenantId, Instant cutoff, int limit) {
        TenantContext.set(tenantId);
        try {
            List<Object[]> rows = readTx.execute(s -> errorEventRepository.findUnprocessed(cutoff, limit));
            return rows == null ? List.of() : rows.stream().map(row -> ((Number) row[1]).longValue()).toList();
        } finally {
            TenantContext.clear();
        }
    }
}
