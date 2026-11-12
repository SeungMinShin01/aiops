package dev.aiops.server.model.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// AI-86 : "지금 TenantContext 에 설정된 팀" 하나의 현황 수치를 읽는다 (D-082)
// error_event, heartbeat_last 는 RLS 대상이라 팀 설정이 있는 트랜잭션 안에서만 그 팀의 행이 보인다.
// 호출하는 쪽(AdminOverviewService)이 TenantContext.set → 이 메서드 → finally clear 를 팀마다 한다.
// 별도 Bean 이어야 @Transactional 프록시가 동작한다 (같은 클래스 안에서 부르면 프록시를 안 탐)
@Component
public class TenantStatsQuery {

    // AI-86 : 한 팀의 수치. lastSeen 은 한 번도 신호가 없으면 null
    public record TenantStats(long errors24h, Instant lastSeenLocal, Instant lastSeenProd) {
    }

    @PersistenceContext
    private EntityManager em;

    // 짧은 읽기 전용 트랜잭션. 트랜잭션이 열릴 때 TenantTransactionManager 가 app.tenant_id 를 DB 에 넣는다
    @Transactional(readOnly = true)
    public TenantStats collect() {
        // 1. 최근 24시간 에러 수 (RLS 가 현재 팀의 행만 남김)
        long errors24h = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM error_event WHERE received_at > now() - interval '24 hours'")
                .getSingleResult()).longValue();

        // 2. env 별 마지막 신호 시각 (D-086: app_snapshot 이 아니라 heartbeat_last 를 쓴다)
        Instant local = null;
        Instant prod = null;
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT env, max(last_seen_at) FROM heartbeat_last GROUP BY env").getResultList();
        for (Object[] row : rows) {
            Instant seen = toInstant(row[1]);
            if ("local".equals(row[0])) {
                local = seen;
            } else if ("prod".equals(row[0])) {
                prod = seen;
            }
        }
        return new TenantStats(errors24h, local, prod);
    }

    // 네이티브 쿼리의 timestamptz 는 드라이버·하이버네이트 설정에 따라 타입이 다르게 올 수 있어서 모두 Instant 로 바꾼다
    private static Instant toInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime offset) {
            return offset.toInstant();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        throw new IllegalStateException("알 수 없는 시각 타입: " + value.getClass().getName());
    }
}