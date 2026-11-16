package dev.aiops.server.model.repository;

import dev.aiops.server.model.entity.ErrorGroupEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ErrorGroupRepository extends JpaRepository<ErrorGroupEntity, Long> {
    // 같은 팀 + 같은 지문 = 같은 그룹 (6단계)
    Optional<ErrorGroupEntity> findByTenantIdAndFingerprint(Long tenantId, String fingerprint);

    // AI-56 : 설명이 비어 있을 때만 채운다. 바뀐 행 수를 돌려줌 (0이면 다른 스레드가 먼저 채운 것)
    // 엔티티를 고쳐 save 하지 않는 이유: 트랜잭션 밖에서 읽은 엔티티로 저장하면 다른 칸(횟수 등)까지 옛 값으로 덮는다
    @Modifying
    @Query("""
            UPDATE ErrorGroupEntity g
            SET g.aiExplanation = :explanation, g.aiExplainedAt = :explainedAt
            WHERE g.id = :id AND g.tenantId = :tenantId AND g.aiExplanation IS NULL
            """)
    int updateExplanationIfNull(@Param("id") Long id, @Param("tenantId") Long tenantId,
                                @Param("explanation") String explanation,
                                @Param("explainedAt") Instant explainedAt);
        // AI-57 : 설명이 아직 없는 최근 그룹 id (오래된 순). 팀은 RLS가 거른다
    @Query(value = """
            SELECT id FROM error_group
            WHERE ai_explanation IS NULL AND first_seen_at > CAST(:since AS timestamptz)
            ORDER BY first_seen_at
            LIMIT :batch
            """, nativeQuery = true)
    List<Long> findIdsMissingExplanation(@Param("since") Instant since, @Param("batch") int batch);
}