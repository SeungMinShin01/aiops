package dev.aiops.server.model.repository;

import dev.aiops.server.model.entity.MembershipEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

// 사용자 ↔ 팀 (V3, RLS 없음 — 팀을 정하기 전에 읽어야 한다, D-023)
// 고정 조건 메서드만 노출한다. 임의 조건 조회를 만들지 않는다
public interface MembershipRepository extends JpaRepository<MembershipEntity, Long> {

    // 이 사용자가 속한 팀 전부 (/api/me, AI-78)
    List<MembershipEntity> findAllByUserId(Long userId);

    // 이 사용자가 이 팀 멤버인지 + 역할 (MembershipGuard, AI-80)
    Optional<MembershipEntity> findByUserIdAndTenantId(Long userId, Long tenantId);

    // 이 사용자가 이 팀 멤버인지만 (디스코드 연결 등 /api/teams/{id}/** 밖 경로에서 직접 확인할 때, AI-93)
    boolean existsByUserIdAndTenantId(Long userId, Long tenantId);
}
