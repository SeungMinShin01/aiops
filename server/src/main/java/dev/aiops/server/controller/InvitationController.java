package dev.aiops.server.controller;

import dev.aiops.server.dto.AcceptResponse;
import dev.aiops.server.security.CurrentUser;
import dev.aiops.server.service.InvitationGoneException;
import dev.aiops.server.service.InvitationNotFoundException;
import dev.aiops.server.service.InvitationService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 초대 링크 API (14단계).
 * <p>
 * 수락은 {@code /api/invitations/{token}/accept} — {@code /api/teams/{teamId}/**}가 아니다.
 * 수락하는 사람은 아직 멤버가 아니라서 MembershipGuard를 타면 403으로 막힌다.
 * 세션 필수(/api 체인), POST라 CSRF 헤더 필요.
 */
@RestController
public class InvitationController {

    private final InvitationService invitationService;

    public InvitationController(InvitationService invitationService) {
        this.invitationService = invitationService;
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
