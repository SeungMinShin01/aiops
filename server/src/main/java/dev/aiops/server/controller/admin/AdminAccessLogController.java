package dev.aiops.server.controller.admin;

import dev.aiops.server.dto.AccessLogRow;
import dev.aiops.server.service.AdminAccessLogService;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// AI-87 : 관리자끼리 서로의 접근 기록을 본다. 이 조회도 기록된다 (재귀가 아니라 한 행 추가일 뿐)
// 관리자 여부는 AdminGuard 가 이미 검사했으므로 여기서 다시 검사하지 않는다
@RestController
@RequestMapping("/api/admin/access-log")
public class AdminAccessLogController {

    private final AdminAccessLogService accessLogService;

    public AdminAccessLogController(AdminAccessLogService accessLogService) {
        this.accessLogService = accessLogService;
    }

    // AI-87 : GET /api/admin/access-log?from=2026-11-01T00:00:00Z&to=...&adminUserId=1&page=0 (모두 생략 가능)
    @GetMapping
    public List<AccessLogRow> list(@RequestParam(required = false) Instant from,
                                   @RequestParam(required = false) Instant to,
                                   @RequestParam(required = false) Long adminUserId,
                                   @RequestParam(defaultValue = "0") int page) {
        return accessLogService.page(from, to, adminUserId, page);
    }
}