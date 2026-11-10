package dev.aiops.server.scheduler;

import dev.aiops.protocol.ProtocolConstants;
import dev.aiops.server.model.entity.TenantEntity;
import dev.aiops.server.model.repository.TenantRepository;
import dev.aiops.server.security.TenantContext;
import dev.aiops.server.service.ErrorEventPurger;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// AI-61 : 매일 새벽에 오래된 원본 이벤트(error_event)만 지움 (local 3일 / prod 14일)
// error_group, app_snapshot, ingest_stat 은 건드리지 않음
// AI-73 : RLS(11단계) 이후에는 팀 설정 없이 DELETE 하면 0건 삭제되고 조용히 끝난다.
//         서버 계정에 RLS 우회를 주지 않고, 팀 목록을 돌며 팀마다 set → 삭제 → clear (D-082)
@Component
public class RetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetentionScheduler.class);

    private final ErrorEventPurger purger;
    private final TenantRepository tenantRepository;
    private final int localDays;    // local 키로 들어온 이벤트를 보관하는 일수
    private final int prodDays;     // prod 키로 들어온 이벤트를 보관하는 일수
    private final int batchSize;    // 한 번에 지우는 최대 행 수
    private final long pauseMs;     // 묶음 사이에 쉬는 시간 (DB 숨 돌리기)

    // 값은 전부 yml (코드에 박지 않음)
    public RetentionScheduler(ErrorEventPurger purger,
                              TenantRepository tenantRepository,
                              @Value("${aiops.retention.local-days:3}") int localDays,
                              @Value("${aiops.retention.prod-days:14}") int prodDays,
                              @Value("${aiops.retention.batch-size:5000}") int batchSize,
                              @Value("${aiops.retention.pause-ms:200}") long pauseMs) {
        this.purger = purger;
        this.tenantRepository = tenantRepository;
        this.localDays = localDays;
        this.prodDays = prodDays;
        this.batchSize = batchSize;
        this.pauseMs = pauseMs;
    }

    // AI-61 : 기본은 매일 04:00 (서울 시간). 예외가 나도 스케줄이 죽지 않게 로그만 남김
    @Scheduled(cron = "${aiops.retention.cron:0 0 4 * * *}", zone = "Asia/Seoul")
    public void run() {
        List<TenantEntity> tenants;
        try {
            tenants = tenantRepository.findAll();   // tenant 는 RLS 없음 (D-023)
        } catch (RuntimeException e) {
            log.error("보관 삭제 실패: 팀 목록 조회 (다음 실행에 다시 시도)", e);
            return;
        }

        long localTotal = 0;
        long prodTotal = 0;
        for (TenantEntity tenant : tenants) {
            if (Thread.currentThread().isInterrupted()) {
                break;   // 서버 종료 중
            }
            TenantContext.set(tenant.getId());
            try {
                localTotal += purgeEnv(tenant.getId(), ProtocolConstants.ENV_LOCAL, localDays);
                prodTotal += purgeEnv(tenant.getId(), ProtocolConstants.ENV_PROD, prodDays);
            } finally {
                TenantContext.clear();
            }
        }
        log.info("보관 삭제 팀={}개 local({}일)={}행 prod({}일)={}행",
                tenants.size(), localDays, localTotal, prodDays, prodTotal);
    }

    // AI-61 : (지금 설정된 팀의) env 하나를 "지운 행이 0이 될 때까지" 묶음 단위로 반복해서 지움
    // 한 env 가 실패해도 다음 env·다음 팀은 계속 하도록 env 마다 try/catch
    private long purgeEnv(Long tenantId, String env, int days) {
        long total = 0;
        try {
            Instant cutoff = Instant.now().minus(Duration.ofDays(days));   // 이 시각보다 오래된 것만 지움
            int deleted;
            do {
                deleted = purger.purgeBatch(env, cutoff, batchSize);
                total += deleted;
                if (deleted > 0 && pauseMs > 0) {
                    Thread.sleep(pauseMs);
                }
            } while (deleted > 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();   // 서버 종료 중이면 멈춤
            log.warn("보관 삭제 중단 tenantId={} env={}", tenantId, env);
        } catch (RuntimeException e) {
            log.error("보관 삭제 실패 tenantId={} env={} (다음 실행에 다시 시도)", tenantId, env, e);
        }
        return total;
    }
}
