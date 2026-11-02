package dev.aiops.server.model.repository;

import dev.aiops.server.model.entity.ApiKeyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKeyEntity, Long> {
    // 폐기되지 않은 키만. 키 인증(2단계)이 쓴다
    Optional<ApiKeyEntity> findByKeyHashAndRevokedAtIsNull(String keyHash);
}