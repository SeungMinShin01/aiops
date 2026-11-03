package dev.aiops.server.model.repository;

import dev.aiops.server.model.entity.ApiKeyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKeyEntity, Long> {
    // 폐기되지 않은 키만. 키 인증(2단계)이 쓴다
    Optional<ApiKeyEntity> findByKeyHashAndRevokedAtIsNull(String keyHash);

    // 키 폐기 후 캐시에서 지울 해시 (AI-74 ApiKeyService.revoke). 엔티티는 해시 getter를 두지 않으므로 여기서만 꺼낸다
    @Query("select k.keyHash from ApiKeyEntity k where k.id = :id")
    Optional<String> findKeyHashById(@Param("id") Long id);
}