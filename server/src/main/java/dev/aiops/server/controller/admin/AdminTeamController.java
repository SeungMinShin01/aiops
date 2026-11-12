package dev.aiops.server.controller.admin;

import dev.aiops.server.dto.BulkCreateRequest;
import dev.aiops.server.dto.OwnerInviteResponse;
import dev.aiops.server.dto.OwnerInviteStatus;
import dev.aiops.server.dto.TeamRow;
import dev.aiops.server.security.CurrentUser;
import dev.aiops.server.service.AdminTeamService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// AI-83 : /api/admin/teams/** . 이후 작업(suspend, resume)이 이 클래스에 이어서 추가함
// 관리자 여부는 AdminGuard 가 이미 검사했고, 호출 기록은 AdminAccessLogInterceptor 가 자동으로 남김
@RestController
@RequestMapping("/api/admin/teams")
public class AdminTeamController {

    private final AdminTeamService adminTeamService;

    public AdminTeamController(AdminTeamService adminTeamService) {
        this.adminTeamService = adminTeamService;
    }

    // AI-83 : 201 [{ teamId, name, status, discordStatus, projectEndDate, ownerLogin }]
    @PostMapping("/bulk")
    public ResponseEntity<List<TeamRow>> bulkCreate(@RequestBody BulkCreateRequest request) {
        List<TeamRow> rows = adminTeamService.bulkCreate(request.cohortId(), request.teams(), CurrentUser.get().appUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(rows);
    }

    // AI-83 : 200 [{ teamId, name, status, discordStatus, projectEndDate, ownerLogin(null 가능) }]
    @GetMapping
    public List<TeamRow> list(@RequestParam Long cohortId) {
        return adminTeamService.listByCohort(cohortId);
    }

    // AI-84 : 201 { inviteUrl, expiresAt }. 토큰 원문은 이 응답에만 나온다. 이전 링크는 자동으로 무효
    @PostMapping("/{teamId}/owner-invite")
    public ResponseEntity<OwnerInviteResponse> issueOwnerInvite(@PathVariable Long teamId) {
        OwnerInviteResponse body = adminTeamService.issueOwnerInvite(teamId, CurrentUser.get().appUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    // AI-84 : 200 { expiresAt, used } (원문 없음). 유효한 링크가 없으면 404
    @GetMapping("/{teamId}/owner-invite")
    public OwnerInviteStatus ownerInviteStatus(@PathVariable Long teamId) {
        return adminTeamService.ownerInviteStatus(teamId);
    }
}