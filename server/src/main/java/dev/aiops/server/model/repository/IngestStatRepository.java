package dev.aiops.server.model.repository;

import dev.aiops.server.model.entity.IngestStatEntity;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IngestStatRepository extends JpaRepository<IngestStatEntity, Long> {

    // AI-62 : 기록(upsert)은 IngestStatRecorder 로 옮김. 여기는 조회만 함
    // AI-62 : 한 팀의 from~to(양쪽 포함) 날짜 통계를 날짜·env 순으로. 16단계 화면·관리자 현황에서 씀 (지금은 테스트용)
    @Query("""
            select s from IngestStatEntity s
            where s.tenantId = :tenantId and s.day between :from and :to
            order by s.day, s.env
            """)
    List<IngestStatEntity> findByTenantAndRange(@Param("tenantId") Long tenantId,
                                                @Param("from") LocalDate from,
                                                @Param("to") LocalDate to);
}