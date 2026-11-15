package dev.aiops.server.controller;

import dev.aiops.server.dto.ErrorGroupFilter;
import dev.aiops.server.dto.ErrorGroupSummary;
import dev.aiops.server.service.ErrorGroupQueryService;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 콘솔 에러 화면 API (AI-95 목록). /api/teams/{teamId} 아래라 MembershipGuard 가 소속을 확인하고
 * TenantContext 를 정한다 (규칙 12). 팀 번호는 URL 에서 가드가 읽으므로 이 컨트롤러는 따로 받지 않는다 —
 * 본문·쿼리에서 다른 팀 id 를 받는 코드가 아예 없게 하려는 것.
 */
@RestController
@RequestMapping("/api/teams/{teamId}/errors")
public class ErrorGroupController {

    private final ErrorGroupQueryService queryService;

    public ErrorGroupController(ErrorGroupQueryService queryService) {
        this.queryService = queryService;
    }

    // GET /api/teams/{teamId}/errors?env=local&status=OPEN&from=2026-11-01&to=2026-11-07&sort=lastSeen,desc&page=0&size=20
    @GetMapping
    public Page<ErrorGroupSummary> list(@RequestParam(required = false) String env,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to,
                                        @RequestParam(required = false) String sort,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        if (page < 0) {
            throw new ErrorGroupFilter.InvalidRequestException("page 는 0 이상");
        }
        if (size < 1 || size > ErrorGroupFilter.MAX_SIZE) {
            throw new ErrorGroupFilter.InvalidRequestException("size 는 1 이상 " + ErrorGroupFilter.MAX_SIZE + " 이하");
        }
        ErrorGroupFilter filter = ErrorGroupFilter.fromRequest(env, status, from, to, sort);
        return queryService.list(filter, PageRequest.of(page, size));
    }

    @ExceptionHandler(ErrorGroupFilter.InvalidRequestException.class)
    ResponseEntity<Map<String, String>> invalidRequest(ErrorGroupFilter.InvalidRequestException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "invalid_request", "message", e.getMessage()));
    }
}