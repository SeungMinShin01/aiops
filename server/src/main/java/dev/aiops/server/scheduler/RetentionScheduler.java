package dev.aiops.server.scheduler;

import dev.aiops.protocol.ProtocolConstants;
import dev.aiops.server.service.ErrorEventPurger;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// AI-61 : 매일 새벽에 오래된 원본 이벤트(error_event)만 지움 (local 3일 / prod 14일)
// error_group, app_snapshot, ingest_stat 은 건드리지 않음
@Component
public class RetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetentionScheduler.class);

    private final ErrorEventPurger purger;
    private final int localDays;    // local 키로 들어온 이벤트를 보관하는 일수
    private final int prodDays;     // prod 키로 들어온 이벤트를 보관하는 일수
    private final int batchSize;    // 한 번에 지우는 최대 행 수
    private final long pauseMs;     // 묶음 사이에 쉬는 시간 (DB 숨 돌리기)

    // 값은 전부 yml (코드에 박지 않음)
    public RetentionScheduler(ErrorEventPurger purger,
                              @Value("${aiops.retention.local-days:3}") int localDays,
                              @Value("${aiops.retention.prod-days:14}") int prodDays,
                              @Value("${aiops.retention.batch-size:5000}") int batchSize,
                              @Value("${aiops.retention.pause-ms:200}") long pauseMs) {
        this.purger = purger;
        this.localDays = localDays;
        this.prodDays = prodDays;
        this.batchSize = batchSize;
        this.pauseMs = pauseMs;
    }

    // AI-61 : 기본은 매일 04:00 (서울 시간). 예외가 나도 스케줄이 죽지 않게 로그만 남김
    @Scheduled(cron = "${aiops.retention.cron:0 0 4 * * *}", zone = "Asia/Seoul")
    public void run() {
        purgeEnv(ProtocolConstants.ENV_LOCAL, localDays);
        purgeEnv(ProtocolConstants.ENV_PROD, prodDays);
    }

    // AI-61 : env 하나를 "지운 행이 0이 될 때까지" 묶음 단위로 반복해서 지움
    // 한 env 가 실패해도 다음 env 는 계속 하도록 env 마다 try/catch
    private void purgeEnv(String env, int days) {
        try {
            Instant cutoff = Instant.now().minus(Duration.ofDays(days));   // 이 시각보다 오래된 것만 지움
            long total = 0;
            int deleted;
            do {
                deleted = purger.purgeBatch(env, cutoff, batchSize);
                total += deleted;
                if (deleted > 0 && pauseMs > 0) {
                    Thread.sleep(pauseMs);
                }
            } while (deleted > 0);
            log.info("보관 삭제 env={} 보관={}일 삭제={}행", env, days, total);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();   // 서버 종료 중이면 멈춤
            log.warn("보관 삭제 중단 env={}", env);
        } catch (RuntimeException e) {
            log.error("보관 삭제 실패 env={} (다음 실행에 다시 시도)", env, e);
        }
    }
}