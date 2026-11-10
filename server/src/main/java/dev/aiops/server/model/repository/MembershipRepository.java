package dev.aiops.server.model.repository;

import dev.aiops.server.model.entity.MembershipEntity;
import org.springframework.data.jpa.repository.JpaRepository;

// 사용자 ↔ 팀 (V3, RLS 없음 — 팀을 정하기 전에 읽어야 한다, D-023)
public interface MembershipRepository extends JpaRepository<MembershipEntity, Long> {

    // 이 사용자가 이 팀 멤버인지 (디스코드 연결 등 /api/teams/{id}/** 밖 경로에서 직접 확인할 때)
    boolean existsByUserIdAndTenantId(Long userId, Long tenantId);
}
