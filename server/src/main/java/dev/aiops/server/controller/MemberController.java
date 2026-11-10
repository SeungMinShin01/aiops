package dev.aiops.server.controller;

import dev.aiops.server.dto.MemberSummary;
import dev.aiops.server.security.CurrentUser;
import dev.aiops.server.security.RequireOwner;
import dev.aiops.server.service.MemberService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 팀원 목록·내보내기 (AI-89). /api/teams/{teamId} 아래라 MembershipGuard가 먼저 소속을 확인한다.
 * 목록은 멤버 누구나, 내보내기는 조장만 (@RequireOwner). 프론트 버튼 숨김에 기대지 않는다.
 */
@RestController
@RequestMapping("/api/teams/{teamId}/members")
public class MemberController {

    private final MemberService memberService;

    public MemberController(MemberService memberService) {
        this.memberService = memberService;
    }

    @GetMapping
    public List<MemberSummary> list(@PathVariable Long teamId) {
        return memberService.list(teamId);
    }

    @RequireOwner
    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> remove(@PathVariable Long teamId, @PathVariable Long userId) {
        memberService.remove(teamId, userId, CurrentUser.get().appUserId());
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(MemberService.CannotRemoveSelfException.class)
    ResponseEntity<Map<String, String>> removeSelf() {
        return ResponseEntity.badRequest().body(Map.of("error", "cannot_remove_self"));
    }

    @ExceptionHandler(MemberService.MemberNotFoundException.class)
    ResponseEntity<Map<String, String>> notMember() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "member_not_found"));
    }
}
