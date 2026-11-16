package dev.aiops.server.controller;

import dev.aiops.server.dto.ErrorGroupDetail;
import dev.aiops.server.dto.ErrorGroupFilter;
import dev.aiops.server.dto.ErrorGroupSummary;
import dev.aiops.server.dto.FeedbackChange;
import dev.aiops.server.dto.GroupFeedbackResult;
import dev.aiops.server.dto.GroupStatusResult;
import dev.aiops.server.dto.StatusChange;
import dev.aiops.server.service.ErrorGroupCommandService;
import dev.aiops.server.service.ErrorGroupQueryService;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 콘솔 에러 화면 API (AI-95 목록, AI-96 상세, AI-97 상태 변경·평가). /api/teams/{teamId} 아래라 MembershipGuard 가 소속을 확인하고
 * TenantContext 를 정한다 (규칙 12). 팀 번호는 URL 에서 가드가 읽으므로 이 컨트롤러는 따로 받지 않는다 —
 * 본문·쿼리에서 다른 팀 id 를 받는 코드가 아예 없게 하려는 것.
 */
@RestController
@RequestMapping("/api/teams/{teamId}/errors")
public class ErrorGroupController {

    private final ErrorGroupQueryService queryService;
    private final ErrorGroupCommandService commandService;

    public ErrorGroupController(ErrorGroupQueryService queryService, ErrorGroupCommandService commandService) {
        this.queryService = queryService;
        this.commandService = commandService;
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
        PageRequest pageRequest = ErrorGroupFilter.pageRequest(page, size);   // AI-107 : 검사를 관리자용과 같이 쓰려고 옮김
        ErrorGroupFilter filter = ErrorGroupFilter.fromRequest(env, status, from, to, sort);
        return queryService.list(filter, pageRequest);
    }

    // AI-96 : GET /api/teams/{teamId}/errors/{groupId}. 없거나 다른 팀 그룹이면 404 (403 이 아니다 — 존재 여부를 드러내지 않는다)
    @GetMapping("/{groupId}")
    public ErrorGroupDetail detail(@PathVariable Long groupId) {
        return queryService.detail(groupId);
    }

    // AI-97 : PATCH /api/teams/{teamId}/errors/{groupId}/status  { "status": "RESOLVED" }  (OPEN / RESOLVED / IGNORED)
    @PatchMapping("/{groupId}/status")
    public GroupStatusResult changeStatus(@PathVariable Long groupId, @RequestBody StatusChange body) {
        return commandService.changeStatus(groupId, body.status());
    }

    // AI-97 : PUT /api/teams/{teamId}/errors/{groupId}/feedback  { "feedback": "UP" }  (UP / DOWN). 같은 값을 다시 보내도 같은 결과
    @PutMapping("/{groupId}/feedback")
    public GroupFeedbackResult giveFeedback(@PathVariable Long groupId, @RequestBody FeedbackChange body) {
        return commandService.giveFeedback(groupId, body.feedback());
    }

    @ExceptionHandler(ErrorGroupFilter.InvalidRequestException.class)
    ResponseEntity<Map<String, String>> invalidRequest(ErrorGroupFilter.InvalidRequestException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "invalid_request", "message", e.getMessage()));
    }

    // AI-97 : 허용값 밖이거나 본문이 없거나 JSON 이 깨졌을 때
    @ExceptionHandler({ErrorGroupCommandService.InvalidValueException.class, HttpMessageNotReadableException.class})
    ResponseEntity<Map<String, String>> invalidBody() {
        return ResponseEntity.badRequest().body(Map.of("error", "invalid_request"));
    }

    // AI-97 : 설명이 아직 없는 그룹에 평가를 남기려 할 때
    @ExceptionHandler(ErrorGroupCommandService.NoExplanationException.class)
    ResponseEntity<Map<String, String>> noExplanation() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "no_explanation"));
    }

    @ExceptionHandler(ErrorGroupQueryService.GroupNotFoundException.class)
    ResponseEntity<Map<String, String>> groupNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "group_not_found"));
    }
}