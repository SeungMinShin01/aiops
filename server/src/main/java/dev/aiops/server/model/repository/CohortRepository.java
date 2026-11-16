package dev.aiops.server.model.repository;

import dev.aiops.server.model.entity.CohortEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

// AI-82 : 기수 (V3, 관리자 전용). RLS 없음 (D-023) → 팀 설정 없이 읽고 쓸 수 있음. 고정 조건 메서드만 둠
public interface CohortRepository extends JpaRepository<CohortEntity, Long> {

    // AI-82 : 같은 이름의 기수가 이미 있는지
    boolean existsByName(String name);

    // AI-82 : 최근 만든 것부터
    List<CohortEntity> findAllByOrderByIdDesc();

    // AI-82 : 기수별 팀 수를 쿼리 한 번으로 (tenant 도 RLS 없음). 한 줄 = [cohortId, 팀 수]. 팀이 없는 기수는 안 나옴
    @Query("select t.cohortId, count(t) from TenantEntity t where t.cohortId is not null group by t.cohortId")
    List<Object[]> countTeamsByCohort();
}