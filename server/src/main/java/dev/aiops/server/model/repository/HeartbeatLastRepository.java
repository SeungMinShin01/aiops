package dev.aiops.server.model.repository;

import dev.aiops.server.model.entity.HeartbeatLastEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HeartbeatLastRepository extends JpaRepository<HeartbeatLastEntity, Long> {
    // 이 팀·env에 연결된 인스턴스 목록 (연결됨 = 3분 이내, D-086)
    List<HeartbeatLastEntity> findByTenantIdAndEnv(Long tenantId, String env);

    // AI-98 : env별 [env, 마지막 신호 시각, since 이후 신호를 보낸 호스트 수]. 연결 확인 API가 판정에 쓴다.
    //         RLS 팀 테이블이라 TenantContext가 정해진 트랜잭션 안에서만 부른다 (tenant_id 조건도 같이 둠)
    @Query(value = """
            SELECT env, max(last_seen_at), count(*) FILTER (WHERE last_seen_at > CAST(:since AS timestamptz))
            FROM heartbeat_last
            WHERE tenant_id = :tenantId
            GROUP BY env
            """, nativeQuery = true)
    List<Object[]> lastSeenByEnv(@Param("tenantId") Long tenantId, @Param("since") Instant since);

    // AI-33 : (팀, env, 호스트) 행이 없으면 만들고, 있으면 갱신함.
    // started 가 null 이면 COALESCE 가 기존 started_at 을 그대로 둠 (이벤트 수집 경로는 시작 시각을 모르니까)
    // CAST: null 을 넘겨도 Postgres 가 타입을 알 수 있게 함
    @Modifying
    @Query(value = """
            INSERT INTO heartbeat_last (tenant_id, env, host_hash, app_name, started_at, last_seen_at)
            VALUES (:tenantId, :env, :hostHash, :appName, CAST(:started AS timestamptz), now())
            ON CONFLICT (tenant_id, env, host_hash)
            DO UPDATE SET last_seen_at = now(),
                          app_name = COALESCE(EXCLUDED.app_name, heartbeat_last.app_name),
                          started_at = COALESCE(EXCLUDED.started_at, heartbeat_last.started_at)
            """, nativeQuery = true)
    void touch(@Param("tenantId") Long tenantId,
               @Param("env") String env,
               @Param("hostHash") String hostHash,
               @Param("appName") String appName,
               @Param("started") Instant started);
}