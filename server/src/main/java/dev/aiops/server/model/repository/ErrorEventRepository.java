package dev.aiops.server.model.repository;

import dev.aiops.server.model.entity.ErrorEventEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
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
}