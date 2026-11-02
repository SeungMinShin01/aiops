package dev.aiops.server.model.repository;

import dev.aiops.server.model.entity.ErrorEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ErrorEventRepository extends JpaRepository<ErrorEventEntity, Long> {
}