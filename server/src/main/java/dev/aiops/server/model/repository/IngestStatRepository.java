package dev.aiops.server.model.repository;

import dev.aiops.server.model.entity.IngestStatEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestStatRepository extends JpaRepository<IngestStatEntity, Long> {
}