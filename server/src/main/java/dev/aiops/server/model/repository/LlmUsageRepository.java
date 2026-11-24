package dev.aiops.server.model.repository;

import dev.aiops.server.model.entity.LlmUsageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;

public interface LlmUsageRepository extends JpaRepository<LlmUsageEntity, LocalDate> {
}