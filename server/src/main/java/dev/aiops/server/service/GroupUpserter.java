package dev.aiops.server.service;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// AI-52 : 지문으로 그룹을 찾거나 만들고 횟수를 올림. SQL 한 번(ON CONFLICT)이라 동시에 들어와도 그룹이 두 개 안 생김
@Service
public class GroupUpserter {

    // 그룹을 만들 때 쓰는 값 (처음 본 이벤트 기준). seenAt 은 에러가 난 시각
    public record GroupSummary(String exceptionType, String topFrame, Instant seenAt) {
    }

    // 결과: 그룹 id 와 "이번에 새로 만들어졌는지"
    public record UpsertResult(Long groupId, boolean isNew) {
    }

    // CAST: null 값(topFrame)을 넘겨도 Postgres 가 타입을 알 수 있게 함
    // (xmax = 0) : 방금 INSERT 된 행이면 true, 기존 행을 UPDATE 한 거면 false (Postgres 트릭)
    // last_seen_at: 늦게 처리된 옛 이벤트가 마지막 발생 시각을 되돌리지 않게 GREATEST
    private static final String UPSERT_SQL = """
            INSERT INTO error_group (tenant_id, fingerprint, exception_type, top_frame, first_env,
                                     first_seen_at, last_seen_at, occurrence_count)
            VALUES (:tenantId, :fingerprint, :exceptionType, CAST(:topFrame AS varchar), :env,
                    CAST(:seenAt AS timestamptz), CAST(:seenAt AS timestamptz), 1)
            ON CONFLICT (tenant_id, fingerprint) DO UPDATE
              SET occurrence_count = error_group.occurrence_count + 1,
                  last_seen_at = GREATEST(error_group.last_seen_at, EXCLUDED.last_seen_at)
            RETURNING id, (xmax = 0) AS is_new
            """;

    private final EntityManager em;

    public GroupUpserter(EntityManager em) {
        this.em = em;
    }

    // 호출하는 쪽 트랜잭션에 합류함 (없으면 이 메서드가 새로 시작해서 바로 커밋)
    @Transactional
    public UpsertResult upsert(Long tenantId, String env, String fingerprint, GroupSummary summary) {
        Object[] row = (Object[]) em.createNativeQuery(UPSERT_SQL)
                .setParameter("tenantId", tenantId)
                .setParameter("fingerprint", fingerprint)
                .setParameter("exceptionType", summary.exceptionType())
                .setParameter("topFrame", summary.topFrame())
                .setParameter("env", env)
                .setParameter("seenAt", summary.seenAt())
                .getSingleResult();
        return new UpsertResult(((Number) row[0]).longValue(), (Boolean) row[1]);
    }
}