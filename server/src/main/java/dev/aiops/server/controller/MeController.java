package dev.aiops.server.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.aiops.server.security.AdminProperties;
import dev.aiops.server.security.AppUserPrincipal;
import dev.aiops.server.security.CurrentUser;
import dev.aiops.server.service.AppUserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * GET /api/me — 내 정보와 소속 팀 (AI-78). 세션 없으면 체인이 401.
 * GitHub access token·이메일은 넣지 않는다. isAdmin·role은 표시용이고 권한 판단은 가드가 한다.
 */
@RestController
public class MeController {

    private final AppUserService appUserService;
    private final AdminProperties adminProperties;

    public MeController(AppUserService appUserService, AdminProperties adminProperties) {
        this.appUserService = appUserService;
        this.adminProperties = adminProperties;
    }

    @GetMapping("/api/me")
    public ResponseEntity<MeResponse> me() {
        AppUserPrincipal principal = CurrentUser.get();
        return appUserService.find(principal.appUserId())
                .map(user -> ResponseEntity.ok(new MeResponse(
                        user.getId(),
                        user.getLogin(),
                        user.getName(),
                        adminProperties.isAdmin(user.getGithubId()),
                        appUserService.teamsOf(user.getId()).stream()
                                .map(t -> new TeamItem(t.teamId(), t.name(), t.role().name()))
                                .toList())))
                // 세션은 있는데 사용자 행이 지워졌으면 다시 로그인하게 한다
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    public record MeResponse(long userId, String login, String name,
            @JsonProperty("isAdmin") boolean isAdmin, List<TeamItem> teams) {
    }

    public record TeamItem(long teamId, String name, String role) {
    }
}
