package dev.aiops.server.controller;

import dev.aiops.server.config.DiscordProperties;
import dev.aiops.server.security.AppUserPrincipal;
import dev.aiops.server.security.CurrentUser;
import dev.aiops.server.security.InvalidOAuthStateException;
import dev.aiops.server.security.OAuthState;
import dev.aiops.server.service.DiscordJoinService;
import dev.aiops.server.service.DiscordJoinService.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Map;

/**
 * 팀원 디스코드 연결 (AI-93). 콘솔 [디스코드 연결] 버튼 → 디스코드 동의 → 콜백.
 * <p>
 * - 세션 필수(/api 체인). 경로가 /api/teams/{teamId}/** 가 아니라서 MembershipGuard를 타지 않으므로
 *   서비스가 직접 멤버십을 확인한다 (규칙 12의 취지)
 * - 디스코드는 로그인 수단이 아니라 연결 기능이라 Spring Security OAuth2 로그인에 등록하지 않고 직접 구현
 * - 디스코드 access_token은 서비스 지역 변수로만 쓰고 끝. 여기서는 다루지도 않는다 (D-070)
 * - 콜백은 디스코드에서 돌아오는 최상위 GET 이동이라 SameSite=Lax 세션 쿠키가 실린다
 */
@RestController
public class DiscordJoinController {

    private static final Logger log = LoggerFactory.getLogger(DiscordJoinController.class);
    static final String AUTHORIZE_URL = "https://discord.com/oauth2/authorize";

    private final DiscordJoinService joinService;
    private final OAuthState oauthState;
    private final DiscordProperties discord;
    private final String consoleBaseUrl;

    public DiscordJoinController(DiscordJoinService joinService, OAuthState oauthState, DiscordProperties discord,
            @Value("${aiops.console.base-url:}") String consoleBaseUrl) {
        this.joinService = joinService;
        this.oauthState = oauthState;
        this.discord = discord;
        this.consoleBaseUrl = consoleBaseUrl == null ? "" : consoleBaseUrl.replaceAll("/+$", "");
    }

    /** [디스코드 연결] 버튼: 멤버 아님 403, 팀 채널 준비 전 409, 통과하면 디스코드 동의 화면으로 302 */
    @GetMapping("/api/discord/connect")
    public ResponseEntity<?> connect(@RequestParam Long teamId) {
        AppUserPrincipal user = CurrentUser.get();

        Result result = joinService.checkConnectable(user.appUserId(), teamId);
        if (result != Result.OK) {
            return error(result);
        }
        if (discord.clientId() == null || discord.clientId().isBlank()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "discord_not_configured"));
        }

        String state;
        try {
            state = joinService.createState(user.appUserId(), teamId);
        } catch (IllegalStateException e) {   // AIOPS_STATE_SECRET 없음
            log.warn("디스코드 연결 불가: state 비밀키 미설정");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "discord_not_configured"));
        }

        URI authorize = UriComponentsBuilder.fromUriString(AUTHORIZE_URL)
                .queryParam("client_id", discord.clientId())
                .queryParam("redirect_uri", discord.redirectUri())
                .queryParam("response_type", "code")
                .queryParam("scope", "identify guilds.join")   // 이 두 개만 (D-070)
                .queryParam("state", state)
                .queryParam("prompt", "consent")
                .encode()
                .build()
                .toUri();
        return ResponseEntity.status(HttpStatus.FOUND).location(authorize).build();
    }

    /**
     * 디스코드 콜백: state 위조·만료·다른 사용자 → 400.
     * 성공 → 콘솔 팀 설정 화면 ?discord=connected, 디스코드 호출 실패 → ?discord=failed.
     */
    @GetMapping("/api/discord/callback")
    public ResponseEntity<?> callback(@RequestParam(required = false) String code,
            @RequestParam(required = false) String state) {
        AppUserPrincipal user = CurrentUser.get();
        Long teamId = oauthState.verify(state).teamId();   // 실패하면 InvalidOAuthStateException → 400
        if (code == null || code.isBlank()) {              // 사용자가 디스코드 동의 화면에서 취소한 경우
            return redirectToConsole(teamId, "failed");
        }

        Result result = joinService.complete(user.appUserId(), code, state);
        return switch (result) {
            case OK -> redirectToConsole(teamId, "connected");
            case FAILED -> redirectToConsole(teamId, "failed");
            default -> error(result);   // NOT_MEMBER 403, NOT_READY·DISCORD_ACCOUNT_IN_USE 409
        };
    }

    @ExceptionHandler(InvalidOAuthStateException.class)
    ResponseEntity<Map<String, String>> invalidState() {
        return ResponseEntity.badRequest().body(Map.of("error", "invalid_state"));
    }

    private ResponseEntity<Map<String, String>> error(Result result) {
        return switch (result) {
            case NOT_MEMBER -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "not_member"));
            case NOT_READY -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "discord_not_ready"));
            case DISCORD_ACCOUNT_IN_USE ->
                    ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "discord_account_in_use"));
            default -> ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "discord_failed"));
        };
    }

    private ResponseEntity<Void> redirectToConsole(Long teamId, String outcome) {
        URI target = URI.create(consoleBaseUrl + "/teams/" + teamId + "/settings?discord=" + outcome);
        return ResponseEntity.status(HttpStatus.FOUND).location(target).build();
    }
}
