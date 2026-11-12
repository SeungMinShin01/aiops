package dev.aiops.server.controller;

import dev.aiops.server.dto.ApiKeySummary;
import dev.aiops.server.service.ApiKeyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 팀 키 목록 (AI-88). 조장·팀원 누구나 조회만 (발급·폐기는 관리자, D-092).
 * /api/teams/{teamId} 아래라 MembershipGuard가 먼저 소속을 확인한다. 원문·해시 없음.
 */
@RestController
public class TeamKeyController {

    private final ApiKeyService apiKeyService;

    public TeamKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @GetMapping("/api/teams/{teamId}/keys")
    public List<ApiKeySummary> list(@PathVariable Long teamId) {
        return apiKeyService.list(teamId);
    }
}
