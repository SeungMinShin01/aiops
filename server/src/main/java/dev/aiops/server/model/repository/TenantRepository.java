package dev.aiops.server.model.repository;

import dev.aiops.server.model.entity.TenantEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantRepository extends JpaRepository<TenantEntity, Long> {
}