package dev.aiops.server.service;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// AI-62 : 팀·env·날짜별로 "받은 수 / Starter 가 버린 수"를 ingest_stat 에 더해 쌓음
// 2단계(IngestService)에 흩어져 있던 통계 코드를 이 클래스로 모음
@Service
public class IngestStatRecorder {

    private static final Logger log = LoggerFactory.getLogger(IngestStatRecorder.class);

    // AI-62 : 날짜는 Asia/Seoul 기준 (DB 의 CURRENT_DATE 는 서버 시간대를 따라서 자바에서 계산해 넘김)
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final EntityManager em;

    public IngestStatRecorder(EntityManager em) {
        this.em = em;
    }

    // AI-62 : 호출한 쪽(IngestService.store)의 트랜잭션에 같이 묶임 → 이벤트 저장이 실패하면 통계도 같이 취소됨
    // (별도 트랜잭션·@Async 로 빼지 않음)
    @Transactional
    public void record(Long tenantId, String env, int received, int dropped) {
        // AI-62 : droppedCount 는 Starter 가 보낸 값 그대로. 음수일 때만 0 으로 보정하고 경고
        // (int 라서 null 은 올 수 없음. 본문에 값이 없으면 JSON 변환 단계에서 0)
        int safeDropped = dropped;
        if (dropped < 0) {
            log.warn("droppedCount 가 음수라 0 으로 보정 tenantId={} env={} dropped={}", tenantId, env, dropped);
            safeDropped = 0;
        }

        // AI-62 : 읽고 더하는 게 아니라 DB 가 한 문장(ON CONFLICT)으로 처리 → 동시에 와도 행이 하나, 숫자도 안 꼬임
        em.createNativeQuery("""
                        INSERT INTO ingest_stat (tenant_id, env, day, received_count, dropped_count)
                        VALUES (:tenantId, :env, :day, :received, :dropped)
                        ON CONFLICT (tenant_id, env, day) DO UPDATE
                          SET received_count = ingest_stat.received_count + EXCLUDED.received_count,
                              dropped_count  = ingest_stat.dropped_count  + EXCLUDED.dropped_count
                        """)
                .setParameter("tenantId", tenantId)
                .setParameter("env", env)
                .setParameter("day", LocalDate.now(SEOUL))
                .setParameter("received", received)
                .setParameter("dropped", safeDropped)
                .executeUpdate();
    }
}