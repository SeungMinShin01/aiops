package dev.aiops.server.controller.admin;

import dev.aiops.server.dto.IssueKeyRequest;
import dev.aiops.server.dto.KeyIssued;
import dev.aiops.server.security.CurrentUser;
import dev.aiops.server.service.AdminApiException;
import dev.aiops.server.service.ApiKeyService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 키 발급·폐기 (AI-88, D-092). /api/admin/** 라서 세션 → AdminGuard → AdminAccessLogInterceptor(AI-87)를
 * 통과한다 (접근 기록 자동, 규칙 15). 응답 본문은 기록하지 않으므로 키 원문이 기록에 남지 않는다.
 * 원문은 관리자가 디스코드 DM으로 1회 전달 (local → 조장, prod → 배포 담당). 채널·로그에 남기지 않는다 (규칙 40).
 */
@RestController
@RequestMapping("/api/admin/teams/{teamId}/keys")
public class AdminKeyController {

    private final ApiKeyService apiKeyService;

    public AdminKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    /** 201 { keyId, env, key } — key가 원문, 이 응답 1회만 */
    @PostMapping
    public ResponseEntity<KeyIssued> issue(@PathVariable Long teamId, @RequestBody IssueKeyRequest request) {
        KeyIssued issued = apiKeyService.issue(teamId, request.env(), CurrentUser.get().appUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(issued);
    }

    /** 204. 그 팀 키가 아니면 404. 폐기 직후 그 키로 /v1 요청은 401 (캐시 무효화) */
    @DeleteMapping("/{keyId}")
    public ResponseEntity<Void> revoke(@PathVariable Long teamId, @PathVariable Long keyId) {
        if (!apiKeyService.revoke(teamId, keyId)) {
            throw new AdminApiException(HttpStatus.NOT_FOUND, "KEY_NOT_FOUND", "이 팀의 키가 아닙니다");
        }
        return ResponseEntity.noContent().build();
    }
}
