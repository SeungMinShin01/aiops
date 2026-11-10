package dev.aiops.server.model.repository;

import dev.aiops.server.model.entity.InvitationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

// 초대 링크 (V3, RLS 없음 — 수락하는 사람은 아직 팀 멤버가 아니다, D-023)
// 조회는 token_hash·tenant_id가 고정된 메서드만. findAll 같은 임의 조회를 쓰지 않는다
public interface InvitationRepository extends JpaRepository<InvitationEntity, Long> {

    // 초대 수락 (AI-105). 원문이 아니라 SHA-256 해시로만 찾는다 (규칙 10)
    @Query("select i from InvitationEntity i where i.tokenHash = :tokenHash")
    Optional<InvitationEntity> findByTokenHash(@Param("tokenHash") String tokenHash);

    // 사용 횟수 +1 — 조건부 UPDATE 한 번이라 같은 토큰을 동시에 수락해도 max_uses를 넘지 않는다.
    // "조회 후 +1 저장"은 동시 요청에 뚫린다. 영향 행 0이면 다 썼거나 만료된 것 (410)
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE invitation SET used_count = used_count + 1 "
            + "WHERE id = :id AND used_count < max_uses AND expires_at > now()", nativeQuery = true)
    int incrementUsedCount(@Param("id") Long id);
}
