package dev.aiops.server.model.repository;

import dev.aiops.server.model.entity.HeartbeatLastEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HeartbeatLastRepository extends JpaRepository<HeartbeatLastEntity, Long> {
    // 이 팀·env에 연결된 인스턴스 목록 (연결됨 = 3분 이내, D-086)
    List<HeartbeatLastEntity> findByTenantIdAndEnv(Long tenantId, String env);

    // (팀, env, 호스트) 행이 없으면 만들고, 있으면 마지막 본 시각만 지금으로 갱신
    @Modifying
    @Query(value = """
            INSERT INTO heartbeat_last (tenant_id, env, host_hash, app_name, last_seen_at)
            VALUES (:tenantId, :env, :hostHash, :appName, now())
            ON CONFLICT (tenant_id, env, host_hash)
            DO UPDATE SET last_seen_at = now(),
                          app_name = COALESCE(EXCLUDED.app_name, heartbeat_last.app_name)
            """, nativeQuery = true)
    void upsertLastSeen(@Param("tenantId") Long tenantId,
                        @Param("env") String env,
                        @Param("hostHash") String hostHash,
                        @Param("appName") String appName);

    // AI-33 추가: hello 용. 행이 없으면 만들고, 있으면 started_at(이번 실행 시작)과 last_seen_at 을 둘 다 지금으로 갱신
    @Modifying
    @Query(value = """
            INSERT INTO heartbeat_last (tenant_id, env, host_hash, app_name, started_at, last_seen_at)
            VALUES (:tenantId, :env, :hostHash, :appName, now(), now())
            ON CONFLICT (tenant_id, env, host_hash)
            DO UPDATE SET started_at = now(),
                          last_seen_at = now(),
                          app_name = COALESCE(EXCLUDED.app_name, heartbeat_last.app_name)
            """, nativeQuery = true)
    void upsertHello(@Param("tenantId") Long tenantId,
                     @Param("env") String env,
                     @Param("hostHash") String hostHash,
                     @Param("appName") String appName);
}