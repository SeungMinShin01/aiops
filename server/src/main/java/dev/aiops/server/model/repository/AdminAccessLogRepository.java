package dev.aiops.server.model.repository;

import dev.aiops.server.model.entity.AdminAccessLogEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

// AI-87 : 관리자 접근 기록. 추가만 하는 테이블이라 JpaRepository 가 아니라 Repository 를 씀
// (JpaRepository 를 쓰면 delete·deleteAll 같은 메서드가 같이 생김). save 와 조회만 둔다. RLS 없음 (D-023)
public interface AdminAccessLogRepository extends Repository<AdminAccessLogEntity, Long> {

    AdminAccessLogEntity save(AdminAccessLogEntity entity);

    // AI-87 : from 이상, to 미만 기간의 기록을 최신 순으로
    @Query("""
            select l from AdminAccessLogEntity l
            where l.at >= :from and l.at < :to
            order by l.at desc, l.id desc
            """)
    List<AdminAccessLogEntity> findInRange(@Param("from") Instant from, @Param("to") Instant to, Pageable pageable);

    // AI-87 : 위와 같고 특정 관리자만
    @Query("""
            select l from AdminAccessLogEntity l
            where l.adminUserId = :adminUserId and l.at >= :from and l.at < :to
            order by l.at desc, l.id desc
            """)
    List<AdminAccessLogEntity> findInRangeByAdmin(@Param("adminUserId") Long adminUserId,
                                                  @Param("from") Instant from, @Param("to") Instant to,
                                                  Pageable pageable);
}