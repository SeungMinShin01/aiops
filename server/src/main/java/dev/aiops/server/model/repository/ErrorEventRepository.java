package dev.aiops.server.model.repository;

import dev.aiops.server.model.entity.ErrorEventEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ErrorEventRepository extends JpaRepository<ErrorEventEntity, Long> {

    // AI-53 : 처리 안 된 이벤트 중 cutoff 보다 오래된 것을 받은 순서로 batch 개까지. 결과 한 줄 = [tenant_id, id]
    // 팀 경계를 넘는 관리용 조회 (11단계 RLS 가 켜지면 팀별 조회로 바꿈)
    @Query(value = """
            SELECT tenant_id, id FROM error_event
            WHERE processed = false AND received_at < CAST(:cutoff AS timestamptz)
            ORDER BY received_at
            LIMIT :batch
            """, nativeQuery = true)
    List<Object[]> findUnprocessed(@Param("cutoff") Instant cutoff, @Param("batch") int batch);

    // AI-61 : env 가 같고 cutoff 보다 오래된 이벤트를 batch 개까지만 지우고, 지운 개수를 돌려줌
    // Postgres 의 DELETE 에는 LIMIT 이 없어서 "id 를 batch 개 골라서 그 id 만 지움" 형태로 씀
    // 관리용 삭제라 팀 경계를 넘음 (11단계 RLS 가 켜지면 팀별로 바꿈)
    @Modifying
    @Query(value = """
            DELETE FROM error_event
            WHERE id IN (
                SELECT id FROM error_event
                WHERE env = :env AND received_at < CAST(:cutoff AS timestamptz)
                ORDER BY received_at
                LIMIT :batch
            )
            """, nativeQuery = true)
    int deleteOlderThan(@Param("env") String env, @Param("cutoff") Instant cutoff, @Param("batch") int batch);
}