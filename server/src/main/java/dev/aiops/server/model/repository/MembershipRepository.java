package dev.aiops.server.model.repository;

import dev.aiops.server.model.entity.MembershipEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    // 팀원 목록 (14단계)
    List<MembershipEntity> findAllByTenantId(Long tenantId);

    // 내보내기 (AI-89). 영향 행 수를 돌려준다 (0이면 그 팀 멤버가 아님 → 404).
    // 짧은 자기 트랜잭션으로 바로 커밋 → 그 사람의 다음 요청부터 MembershipGuard가 403
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from MembershipEntity m where m.userId = :userId and m.tenantId = :tenantId")
    int deleteByUserIdAndTenantId(@Param("userId") Long userId, @Param("tenantId") Long tenantId);

    // AI-86 : 팀들의 조장(OWNER) 로그인 이름 (한 줄 = [팀 번호, 로그인]). role = OWNER 와 tenant_id IN (...) 이 고정된 조회
    @Query("select m.tenantId, u.login from MembershipEntity m, AppUserEntity u "
            + "where u.id = m.userId and m.role = dev.aiops.server.model.entity.MemberRole.OWNER "
            + "and m.tenantId in :tenantIds")
    List<Object[]> findOwnerRows(@Param("tenantIds") Collection<Long> tenantIds);

    // AI-86 : 관리자 전체 현황용. 팀 번호 → 조장 로그인 (조장이 없는 팀은 키가 없음). 한 번의 조회로 가져온다
    default Map<Long, String> findOwnersByTenantIds(Collection<Long> tenantIds) {
        Map<Long, String> owners = new HashMap<>();
        if (tenantIds == null || tenantIds.isEmpty()) {
            return owners;
        }
        for (Object[] row : findOwnerRows(tenantIds)) {
            owners.putIfAbsent(((Number) row[0]).longValue(), (String) row[1]);   // 조장이 둘이어도 첫 번째만
        }
        return owners;
    }
}