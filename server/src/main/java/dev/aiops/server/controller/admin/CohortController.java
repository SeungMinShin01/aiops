package dev.aiops.server.controller.admin;

import dev.aiops.server.dto.CohortRequest;
import dev.aiops.server.dto.CohortResponse;
import dev.aiops.server.service.CohortService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// AI-82 : 기수 생성·목록. /api/admin/** 라서 세션 → AdminGuard → AdminAccessLogInterceptor 를 통과함
// 관리자 여부는 AdminGuard 가 이미 검사했으므로 여기서 다시 검사하지 않음
@RestController
@RequestMapping("/api/admin/cohorts")
public class CohortController {

    private final CohortService cohortService;

    public CohortController(CohortService cohortService) {
        this.cohortService = cohortService;
    }

    // AI-82 : 201 { cohortId, name, startDate, endDate, teamCount }
    @PostMapping
    public ResponseEntity<CohortResponse> create(@RequestBody CohortRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(cohortService.create(request));
    }

    // AI-82 : 200 [{ cohortId, name, startDate, endDate, teamCount }] 최신 생성 순
    @GetMapping
    public List<CohortResponse> list() {
        return cohortService.list();
    }
}