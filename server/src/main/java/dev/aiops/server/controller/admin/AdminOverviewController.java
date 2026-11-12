package dev.aiops.server.controller.admin;

import dev.aiops.server.dto.OverviewRow;
import dev.aiops.server.service.AdminOverviewService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// AI-86 : 관리자 전체 현황. /api/admin/** 라서 세션 → AdminGuard → AdminAccessLogInterceptor 를 통과함
// 조회도 접근 기록에 자동으로 남는다 (D-024). 여기서 따로 기록하지 않는다
@RestController
@RequestMapping("/api/admin/overview")
public class AdminOverviewController {

    private final AdminOverviewService adminOverviewService;

    public AdminOverviewController(AdminOverviewService adminOverviewService) {
        this.adminOverviewService = adminOverviewService;
    }

    // AI-86 : 200 [{ teamId, name, cohort, status, ownerLogin, lastSeenLocal, lastSeenProd, errors24h, discordStatus }]
    // cohortId 를 생략하면 전체 팀
    @GetMapping
    public List<OverviewRow> overview(@RequestParam(required = false) Long cohortId) {
        return adminOverviewService.overview(cohortId);
    }
}