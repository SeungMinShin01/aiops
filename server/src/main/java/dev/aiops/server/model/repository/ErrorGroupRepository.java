package dev.aiops.server.model.repository;

import dev.aiops.server.model.entity.ErrorGroupEntity;
import java.time.Instant;
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
}