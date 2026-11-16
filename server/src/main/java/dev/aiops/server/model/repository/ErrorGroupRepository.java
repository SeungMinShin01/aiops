package dev.aiops.server.model.repository;

import dev.aiops.server.model.entity.ErrorGroupEntity;
import dev.aiops.server.model.entity.Feedback;
import dev.aiops.server.model.entity.GroupStatus;
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

    // AI-97 : 상태를 바꾼다. 바뀐 행 수를 돌려줌 (0이면 없는 그룹 또는 다른 팀 그룹 → RLS 가 걸러서 0)
    // 엔티티를 고쳐 save 하지 않는 이유는 위와 같다 (횟수 등 다른 칸을 옛 값으로 덮지 않으려고 이 칸만 UPDATE)
    @Modifying
    @Query("UPDATE ErrorGroupEntity g SET g.status = :status WHERE g.id = :id")
    int updateStatus(@Param("id") Long id, @Param("status") GroupStatus status);

    // AI-97 : 설명이 있는 그룹에만 평가를 남긴다. 0이면 없는 그룹이거나 설명이 아직 없는 그룹 (서비스가 existsById 로 구분)
    @Modifying
    @Query("UPDATE ErrorGroupEntity g SET g.feedback = :feedback WHERE g.id = :id AND g.aiExplanation IS NOT NULL")
    int updateFeedbackIfExplained(@Param("id") Long id, @Param("feedback") Feedback feedback);

    // AI-97 : RESOLVED 인 그룹만 OPEN 으로 되돌린다. 1이면 "이번에 다시 열렸다" (동시에 두 건이 와도 한 건만 1을 받는다)
    @Modifying
    @Query("UPDATE ErrorGroupEntity g SET g.status = :open WHERE g.id = :id AND g.status = :resolved")
    int reopenIfResolved(@Param("id") Long id, @Param("open") GroupStatus open, @Param("resolved") GroupStatus resolved);
}