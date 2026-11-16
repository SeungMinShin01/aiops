package dev.aiops.server.service;

import dev.aiops.server.dto.CohortRequest;
import dev.aiops.server.dto.CohortResponse;
import dev.aiops.server.model.entity.CohortEntity;
import dev.aiops.server.model.repository.CohortRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// AI-82 : 기수(예: 2026-3기) 생성·목록. 삭제는 만들지 않음 (팀이 달려 있으면 삭제 불가, MVP 범위 밖)
@Service
public class CohortService {

    private final CohortRepository cohorts;

    public CohortService(CohortRepository cohorts) {
        this.cohorts = cohorts;
    }

    // AI-82 : 검증 → 저장. 팀은 여기서 만들지 않음 (다음 작업의 일괄 생성 API)
    @Transactional
    public CohortResponse create(CohortRequest request) {
        // 이름이 비어 있음 → 400
        String name = request == null || request.name() == null ? "" : request.name().trim();
        if (name.isEmpty()) {
            throw new AdminApiException(HttpStatus.BAD_REQUEST, "COHORT_NAME_REQUIRED", "기수 이름이 필요합니다");
        }
        // 종료일이 시작일보다 앞 → 400 (같은 날은 허용)
        if (request.startDate() != null && request.endDate() != null && request.endDate().isBefore(request.startDate())) {
            throw new AdminApiException(HttpStatus.BAD_REQUEST, "COHORT_DATE_ORDER", "종료일이 시작일보다 빠릅니다");
        }
        // 같은 이름이 이미 있음 → 409
        if (cohorts.existsByName(name)) {
            throw new AdminApiException(HttpStatus.CONFLICT, "COHORT_NAME_DUPLICATE", "이미 있는 기수 이름입니다");
        }

        CohortEntity saved = cohorts.save(new CohortEntity(name, request.startDate(), request.endDate()));
        return new CohortResponse(saved.getId(), saved.getName(), saved.getStartDate(), saved.getEndDate(), 0);
    }

    // AI-82 : 최신 생성 순 + 기수별 팀 수
    @Transactional(readOnly = true)
    public List<CohortResponse> list() {
        // 팀 수는 쿼리 한 번으로 모아서 기수 id → 개수 표로 만듦 (기수마다 따로 세지 않음)
        Map<Long, Long> teamCounts = new HashMap<>();
        for (Object[] row : cohorts.countTeamsByCohort()) {
            teamCounts.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return cohorts.findAllByOrderByIdDesc().stream()
                .map(c -> new CohortResponse(c.getId(), c.getName(), c.getStartDate(), c.getEndDate(),
                        teamCounts.getOrDefault(c.getId(), 0L)))
                .toList();
    }
}