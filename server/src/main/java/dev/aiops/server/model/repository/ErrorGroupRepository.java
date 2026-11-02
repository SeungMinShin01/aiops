package dev.aiops.server.model.repository;

import dev.aiops.server.model.entity.ErrorGroupEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ErrorGroupRepository extends JpaRepository<ErrorGroupEntity, Long> {
    // 같은 팀 + 같은 지문 = 같은 그룹 (6단계)
    Optional<ErrorGroupEntity> findByTenantIdAndFingerprint(Long tenantId, String fingerprint);
}