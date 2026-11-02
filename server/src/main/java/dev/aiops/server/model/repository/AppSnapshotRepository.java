package dev.aiops.server.model.repository;

import dev.aiops.server.model.entity.AppSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface AppSnapshotRepository extends JpaRepository<AppSnapshotEntity, Long> {
    // "마지막 hello"
    Optional<AppSnapshotEntity> findTopByTenantIdAndEnvOrderByReceivedAtDesc(Long tenantId, String env);
}