package dev.aiops.server.model.repository;

import dev.aiops.server.model.entity.IngestStatEntity;
import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IngestStatRepository extends JpaRepository<IngestStatEntity, Long> {

    // 오늘 행이 없으면 새로 만들고, 있으면 개수를 더함. 읽고 더하는 게 아니라 DB가 한 문장으로 처리해서
    // 동시에 여러 요청이 와도 숫자가 꼬이지 않음
    @Modifying
    @Query(value = """
            INSERT INTO ingest_stat (tenant_id, env, day, received_count, dropped_count)
            VALUES (:tenantId, :env, :day, :received, :dropped)
            ON CONFLICT (tenant_id, env, day)
            DO UPDATE SET received_count = ingest_stat.received_count + EXCLUDED.received_count,
                          dropped_count  = ingest_stat.dropped_count  + EXCLUDED.dropped_count
            """, nativeQuery = true)
    void upsertCounts(@Param("tenantId") Long tenantId,
                      @Param("env") String env,
                      @Param("day") LocalDate day,
                      @Param("received") long received,
                      @Param("dropped") long dropped);
}