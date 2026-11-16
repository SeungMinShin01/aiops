package dev.aiops.server.controller.admin;

import dev.aiops.server.dto.ErrorGroupDetail;
import dev.aiops.server.dto.ErrorGroupFilter;
import dev.aiops.server.dto.ErrorGroupSummary;
import dev.aiops.server.model.repository.TenantRepository;
import dev.aiops.server.security.TenantContext;
import dev.aiops.server.service.AdminApiException;
import dev.aiops.server.service.ErrorGroupQueryService;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 전용 에러 조회 (AI-107, D-103). 한 번에 팀 하나의 에러 그룹 목록·상세를 읽기 전용으로 본다.
 * /api/admin/** 라서 세션 → AdminGuard → AdminAccessLogInterceptor 를 통과한다 (조회가 접근 기록에 자동으로 남는다, 규칙 15).
 * 조회 로직은 팀용 ErrorGroupQueryService 를 그대로 부른다 (새로 쓰지 않는다 — 기준이 두 곳에서 달라지지 않게).
 * RLS 를 우회하지 않는다 (D-082): 팀 번호를 TenantContext 에 set 하고, 같은 앱 계정으로, 끝나면 finally 에서 clear 한다.
 * MembershipGuard 는 건드리지 않는다 — 관리자가 /api/teams/{teamId}/** 로 들어가는 길은 없다 (D-024).
 * 상태 변경·평가는 넣지 않는다 (팀의 몫). 그래서 GET 만 있다.
 */
@RestController
@RequestMapping("/api/admin/teams/{teamId}/errors")
public class AdminErrorController {

    private final ErrorGroupQueryService queryService;
    private final TenantRepository tenants;

    public AdminErrorController(ErrorGroupQueryService queryService, TenantRepository tenants) {
        this.queryService = queryService;
        this.tenants = tenants;
    }

    // GET /api/admin/teams/{teamId}/errors?env=&status=&from=&to=&sort=&page=&size=  (팀용 목록과 같은 파라미터·같은 응답)
    @GetMapping
    public Page<ErrorGroupSummary> list(@PathVariable Long teamId,
                                        @RequestParam(required = false) String env,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to,
                                        @RequestParam(required = false) String sort,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        requireTeam(teamId);
        PageRequest pageRequest = ErrorGroupFilter.pageRequest(page, size);
        ErrorGroupFilter filter = ErrorGroupFilter.fromRequest(env, status, from, to, sort);
        TenantContext.set(teamId);
        try {
            return queryService.list(filter, pageRequest);
        } finally {
            TenantContext.clear();   // 예외가 나도 지운다. 안 지우면 다음 요청이 이 팀을 물려받는다
        }
    }

    // GET /api/admin/teams/{teamId}/errors/{groupId}  (팀용 상세와 같은 응답). 그 팀의 그룹이 아니면 404
    @GetMapping("/{groupId}")
    public ErrorGroupDetail detail(@PathVariable Long teamId, @PathVariable Long groupId) {
        requireTeam(teamId);
        TenantContext.set(teamId);
        try {
            return queryService.detail(groupId);
        } finally {
            TenantContext.clear();
        }
    }

    // tenant 테이블은 RLS 가 없어서 팀을 정하기 전에 읽을 수 있다
    private void requireTeam(Long teamId) {
        if (!tenants.existsById(teamId)) {
            throw new AdminApiException(HttpStatus.NOT_FOUND, "TEAM_NOT_FOUND", "없는 팀입니다");
        }
    }

    // 관리자 API 의 에러 형식 { error, message } 로 맞춘다 (AdminExceptionHandler 와 같은 모양)
    @ExceptionHandler(ErrorGroupFilter.InvalidRequestException.class)
    ResponseEntity<Map<String, String>> invalidRequest(ErrorGroupFilter.InvalidRequestException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "INVALID_REQUEST", "message", e.getMessage()));
    }

    // 다른 팀 그룹이거나 없는 그룹. RLS 가 그 팀 밖 행을 0건으로 만들어서 둘이 똑같이 보인다
    @ExceptionHandler(ErrorGroupQueryService.GroupNotFoundException.class)
    ResponseEntity<Map<String, String>> groupNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "GROUP_NOT_FOUND", "message", "이 팀의 에러 그룹이 아닙니다"));
    }
}