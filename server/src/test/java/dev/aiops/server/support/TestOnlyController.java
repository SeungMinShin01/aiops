package dev.aiops.server.support;

import dev.aiops.server.security.AppUserPrincipal;
import dev.aiops.server.security.RequireOwner;
import dev.aiops.server.security.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 🔴 권한 E2E(AI-76) 전용. test 프로필에서만 뜬다. 담당자는 수정하지 않는다 (규칙 9).
 * 실제 API(13·14·16단계)와 경로가 겹치지 않도록 전부 _test 경로를 쓴다.
 */
@Profile("test")
@RestController
class TestOnlyController {

    private final JdbcTemplate jdbc;

    TestOnlyController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * GitHub를 거치지 않고 로그인 세션을 만든다. /_test는 어느 보안 체인에도 걸리지 않는 경로다.
     * 세션 저장 위치는 Spring Security 기본값(HttpSessionSecurityContextRepository)이다.
     */
    @PostMapping("/_test/login/{userId}")
    ResponseEntity<Void> login(@PathVariable long userId, HttpServletRequest request) {
        Map<String, Object> row = jdbc.queryForMap("SELECT github_id, login FROM app_user WHERE id = ?", userId);
        AppUserPrincipal principal = new AppUserPrincipal(
                userId, ((Number) row.get("github_id")).longValue(), (String) row.get("login"));

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
        request.getSession(true).setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        return ResponseEntity.ok().build();
    }

    /** 요청이 끝난 뒤 서버 스레드에 팀이 남아 있는지 본다 (MembershipGuard를 거치지 않는 경로) */
    @GetMapping("/api/_test/tenant")
    String tenant() {
        return TenantContext.get().map(String::valueOf).orElse("none");
    }

    /** MembershipGuard를 통과하면 가드가 설정한 팀 번호를 돌려준다 */
    @GetMapping("/api/teams/{teamId}/_test/ping")
    String ping(@PathVariable long teamId) {
        return String.valueOf(TenantContext.require());
    }

    /** 조장 전용 동작 흉내 (실제 키 발급은 14단계) */
    @RequireOwner
    @PostMapping("/api/teams/{teamId}/_test/owner-action")
    ResponseEntity<Void> ownerAction(@PathVariable long teamId) {
        return ResponseEntity.status(201).build();
    }

    /** 관리자 경로 흉내 (실제 overview는 13단계) */
    @GetMapping("/api/admin/_test/ping")
    String adminPing() {
        return "ok";
    }
}