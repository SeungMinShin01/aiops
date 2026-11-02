package dev.aiops.server.model.repository;

import dev.aiops.server.model.entity.HeartbeatLastEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface HeartbeatLastRepository extends JpaRepository<HeartbeatLastEntity, Long> {
    // 이 팀·env에 연결된 인스턴스 목록 (연결됨 = 3분 이내, D-086)
    List<HeartbeatLastEntity> findByTenantIdAndEnv(Long tenantId, String env);
}