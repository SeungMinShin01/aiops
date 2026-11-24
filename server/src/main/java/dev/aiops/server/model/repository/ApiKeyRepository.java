package dev.aiops.server.model.repository;

import dev.aiops.server.model.entity.ApiKeyEntity;
import dev.aiops.server.model.entity.TenantStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

// api_key는 RLS 없음 (팀을 알기 전에 키로 조회한다, D-023) → 팀 기준 조회·삭제에는 tenant_id 조건을 고정한다
public interface ApiKeyRepository extends JpaRepository<ApiKeyEntity, Long> {
    // 폐기되지 않은 키만. 키 인증(2단계)이 쓴다
    Optional<ApiKeyEntity> findByKeyHashAndRevokedAtIsNull(String keyHash);

    // 키 폐기 후 캐시에서 지울 해시 (AI-74 ApiKeyService.revoke). 엔티티는 해시 getter를 두지 않으므로 여기서만 꺼낸다
    @Query("select k.keyHash from ApiKeyEntity k where k.id = :id")
    Optional<String> findKeyHashById(@Param("id") Long id);

    // AI-85 : 팀 정지 시 캐시에서 지울 그 팀 키 해시 전부 (폐기된 키 포함, 지워도 무해). 엔티티는 해시 getter가 없으므로 여기서만 꺼낸다
    @Query("select k.keyHash from ApiKeyEntity k where k.tenantId = :tenantId")
    List<String> findAllHashesByTenantId(@Param("tenantId") Long tenantId);

    // AI-85 : 키가 속한 팀의 상태. ApiKeyResolver 가 정지된 팀의 키를 거부할 때 쓴다 (tenant 는 RLS 없음)
    @Query("select t.status from TenantEntity t where t.id = :tenantId")
    Optional<TenantStatus> findTenantStatus(@Param("tenantId") Long tenantId);

    // 팀 키 목록 (AI-88, 최근 발급 순)
    List<ApiKeyEntity> findAllByTenantIdOrderByCreatedAtDesc(Long tenantId);

    // 그 팀의 키 1개 (다른 팀 키 id를 넣어도 안 나온다, AI-88)
    Optional<ApiKeyEntity> findByIdAndTenantId(Long id, Long tenantId);

    // 마지막 사용 시각 (AI-88). 키 캐시(AI-74)가 비었을 때만 불리므로 키마다 1분에 최대 1번
    @Transactional
    @Modifying
    @Query("update ApiKeyEntity k set k.lastUsedAt = :at where k.id = :id")
    int touchLastUsed(@Param("id") Long id, @Param("at") Instant at);
}