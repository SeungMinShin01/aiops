package dev.aiops.server.scheduler;

import dev.aiops.server.model.repository.ErrorEventRepository;
import dev.aiops.server.service.ErrorProcessor;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// AI-53 : 처리 안 된(processed=false) 이벤트를 주기적으로 주워서 다시 처리함
@Component
public class ReprocessScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReprocessScheduler.class);

    private final ErrorEventRepository errorEventRepository;
    private final ErrorProcessor errorProcessor;
    private final Duration minAge;   // 이 시간보다 오래된 것만 줍는 유예 (방금 들어와 처리 풀에 있는 것과 안 겹치게)
    private final int batch;         // 한 번에 줍는 최대 개수

    // 값은 전부 yml (코드에 박지 않음)
    public ReprocessScheduler(ErrorEventRepository errorEventRepository,
                              ErrorProcessor errorProcessor,
                              @Value("${aiops.reprocess.min-age:PT2M}") String minAge,
                              @Value("${aiops.reprocess.batch:500}") int batch) {
        this.errorEventRepository = errorEventRepository;
        this.errorProcessor = errorProcessor;
        this.minAge = Duration.parse(minAge);
        this.batch = batch;
    }

    // AI-53 : 이전 실행이 끝난 뒤 interval 만큼 쉬고 다시 실행. 이 메서드는 짧게 끝남 (처리 자체는 처리 풀이 함)
    @Scheduled(fixedDelayString = "${aiops.reprocess.interval:PT5M}",
               initialDelayString = "${aiops.reprocess.initial-delay:PT1M}")
    public void run() {
        try {
            Instant cutoff = Instant.now().minus(minAge);
            List<Object[]> rows = errorEventRepository.findUnprocessed(cutoff, batch);
            if (rows.isEmpty()) {
                return;
            }

            // 팀별로 이벤트 id 를 묶음 (순서 유지)
            Map<Long, List<Long>> byTenant = new LinkedHashMap<>();
            for (Object[] row : rows) {
                Long tenantId = ((Number) row[0]).longValue();
                Long eventId = ((Number) row[1]).longValue();
                byTenant.computeIfAbsent(tenantId, key -> new ArrayList<>()).add(eventId);
            }

            // 팀마다 1번씩 같은 process 를 부름 (@Async 라서 바로 돌아옴)
            for (Map.Entry<Long, List<Long>> entry : byTenant.entrySet()) {
                try {
                    errorProcessor.process(entry.getKey(), entry.getValue());
                } catch (RuntimeException e) {
                    // 한 팀이 실패해도 다음 팀은 계속
                    log.error("재처리 요청 실패 tenantId={}", entry.getKey(), e);
                }
            }
            log.info("재처리 요청 이벤트 {}개 팀 {}개", rows.size(), byTenant.size());
        } catch (RuntimeException e) {
            // 예외가 나도 스케줄이 죽지 않게 로그만 (다음 주기에 다시 실행됨)
            log.error("재처리 스케줄 실패", e);
        }
    }
}