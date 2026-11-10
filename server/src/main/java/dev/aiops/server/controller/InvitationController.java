package dev.aiops.server.controller;

import dev.aiops.server.dto.AcceptResponse;
import dev.aiops.server.dto.CreateInviteRequest;
import dev.aiops.server.dto.InviteCreated;
import dev.aiops.server.dto.InviteSummary;
import dev.aiops.server.security.CurrentUser;
import dev.aiops.server.security.RequireOwner;
import dev.aiops.server.service.InvitationGoneException;
import dev.aiops.server.service.InvitationNotFoundException;
import dev.aiops.server.service.InvitationService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 초대 링크 API (14단계).
 * <p>
 * 수락은 {@code /api/invitations/{token}/accept} — {@code /api/teams/{teamId}/**}가 아니다.
 * 수락하는 사람은 아직 멤버가 아니라서 MembershipGuard를 타면 403으로 막힌다.
 * 세션 필수(/api 체인), POST라 CSRF 헤더 필요.
 * <p>
 * 팀원 링크 발급·목록·무효화는 {@code /api/teams/{teamId}/invitations} — MembershipGuard + @RequireOwner (AI-89).
 * 조장 링크 발급은 여기 없다 (관리자만, 규칙 41 → 13단계).
 */
@RestController
public class InvitationController {

    private final InvitationService invitationService;

    public InvitationController(InvitationService invitationService) {
        this.invitationService = invitationService;
    }

    static final int MAX_USES_DEFAULT = 10;
    static final int MAX_USES_LIMIT = 50;

    /** 팀원 링크 발급 (조장). maxUses 1~50, 비우면 10. 원문은 inviteUrl에 담겨 이 응답 1회만 */
    @RequireOwner
    @PostMapping("/api/teams/{teamId}/invitations")
    public ResponseEntity<?> issue(@PathVariable Long teamId, @RequestBody(required = false) CreateInviteRequest body) {
        int maxUses = (body == null || body.maxUses() == null) ? MAX_USES_DEFAULT : body.maxUses();
        if (maxUses < 1 || maxUses > MAX_USES_LIMIT) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_max_uses"));
        }
        InvitationService.Issued issued = invitationService.issueMemberInvite(teamId, maxUses,
                CurrentUser.get().appUserId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new InviteCreated(issued.invitationId(), issued.inviteUrl(), issued.expiresAt(), issued.maxUses()));
    }

    /** 아직 유효한 링크 목록 (조장). 원문·해시 없음 */
    @RequireOwner
    @GetMapping("/api/teams/{teamId}/invitations")
    public List<InviteSummary> list(@PathVariable Long teamId) {
        return invitationService.listActive(teamId);
    }

    /** 링크 무효화 (조장). 삭제 대신 만료 처리 */
    @RequireOwner
    @DeleteMapping("/api/teams/{teamId}/invitations/{invitationId}")
    public ResponseEntity<Void> expire(@PathVariable Long teamId, @PathVariable Long invitationId) {
        return invitationService.expire(teamId, invitationId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @PostMapping("/api/invitations/{token}/accept")
    public AcceptResponse accept(@PathVariable String token) {
        Long userId = CurrentUser.get().appUserId();
        try {
            return invitationService.accept(token, userId);
        } catch (DataIntegrityViolationException e) {
            // 같은 사람이 같은 링크를 동시에 두 번 누른 경우: 먼저 끝난 쪽이 멤버를 만들었다 → 이미 멤버로 응답
            return invitationService.accept(token, userId);
        }
    }

    @ExceptionHandler(InvitationNotFoundException.class)
    ResponseEntity<Map<String, String>> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "invitation_not_found"));
    }

    @ExceptionHandler(InvitationGoneException.class)
    ResponseEntity<Map<String, String>> gone() {
        return ResponseEntity.status(HttpStatus.GONE).body(Map.of("error", "invitation_gone"));
    }
}
