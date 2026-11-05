package dev.aiops.server.service;

import dev.aiops.server.client.DiscordApiClient;
import dev.aiops.server.client.DiscordApiException;
import dev.aiops.server.client.DiscordOAuthClient;
import dev.aiops.server.model.entity.AppUserEntity;
import dev.aiops.server.model.entity.DiscordStatus;
import dev.aiops.server.model.entity.TenantEntity;
import dev.aiops.server.model.repository.AppUserRepository;
import dev.aiops.server.model.repository.MembershipRepository;
import dev.aiops.server.model.repository.TenantRepository;
import dev.aiops.server.security.InvalidOAuthStateException;
import dev.aiops.server.security.OAuthState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 팀원 디스코드 연결 (AI-93): 디스코드 로그인 → 봇이 우리 서버에 넣고 팀 역할을 붙인다 → 자기 팀 채널만 보인다.
 * <p>
 * 디스코드 사용자 access_token은 이 클래스의 지역 변수로만 쓰고 끝낸다. 저장·로그·예외에 남기지 않는다 (D-070, 규칙 10).
 * 멤버십 확인은 백엔드에서 (콘솔 버튼 숨김은 편의일 뿐, 규칙 12의 취지).
 * 컨트롤러(/api/discord/connect·callback)는 12단계 로그인(세션 사용자) 이후에 붙인다.
 */
@Service
public class DiscordJoinService {

    private static final Logger log = LoggerFactory.getLogger(DiscordJoinService.class);

    /** 연결 가능 여부·결과. 컨트롤러가 HTTP 상태나 콘솔 redirect로 바꾼다 */
    public enum Result {
        /** 연결 가능 (connect) / 연결 완료 (callback) */
        OK,
        /** 이 팀 멤버가 아님 → 403 */
        NOT_MEMBER,
        /** 팀 디스코드 채널이 아직 준비 안 됨(역할 없음) → 409 */
        NOT_READY,
        /** 이 디스코드 계정이 다른 사용자에 이미 연결됨 → 409 */
        DISCORD_ACCOUNT_IN_USE,
        /** 디스코드 호출 실패 → 콘솔 ?discord=failed */
        FAILED
    }

    private final OAuthState oauthState;
    private final DiscordOAuthClient oauth;
    private final DiscordApiClient api;
    private final TenantRepository tenants;
    private final AppUserRepository appUsers;
    private final MembershipRepository memberships;

    public DiscordJoinService(OAuthState oauthState, DiscordOAuthClient oauth, DiscordApiClient api,
            TenantRepository tenants, AppUserRepository appUsers, MembershipRepository memberships) {
        this.oauthState = oauthState;
        this.oauth = oauth;
        this.api = api;
        this.tenants = tenants;
        this.appUsers = appUsers;
        this.memberships = memberships;
    }

    /** [디스코드 연결] 버튼: 멤버 + 팀 채널 준비 완료면 OK. 서명된 state는 createState로 */
    public Result checkConnectable(Long userId, Long teamId) {
        if (!memberships.existsByUserIdAndTenantId(userId, teamId)) {
            return Result.NOT_MEMBER;
        }
        TenantEntity tenant = tenants.findById(teamId).orElse(null);
        if (tenant == null || tenant.getDiscordStatus() != DiscordStatus.READY || isBlank(tenant.getDiscordRoleId())) {
            return Result.NOT_READY;
        }
        return Result.OK;
    }

    /** 디스코드 authorize로 보낼 state (서버 서명, 10분 만료) */
    public String createState(Long userId, Long teamId) {
        return oauthState.create(userId, teamId);
    }

    /**
     * 디스코드 콜백 처리 (본문 4~8단계).
     *
     * @param sessionUserId 지금 로그인한 사용자 (12단계 세션)
     * @throws InvalidOAuthStateException state 위조·만료, 또는 state의 사용자가 세션 사용자와 다름 → 400
     */
    public Result complete(Long sessionUserId, String code, String state) {
        // 4. state 서명·만료 검증 + 세션 사용자와 같은지
        OAuthState.Payload payload = oauthState.verify(state);
        if (!payload.userId().equals(sessionUserId)) {
            throw new InvalidOAuthStateException("user mismatch");
        }
        Long teamId = payload.teamId();

        // 콜백 시점에도 다시 확인 (그사이 내보내졌거나 팀이 바뀌었을 수 있다)
        Result connectable = checkConnectable(sessionUserId, teamId);
        if (connectable != Result.OK) {
            return connectable;
        }
        String roleId = tenants.findById(teamId).orElseThrow().getDiscordRoleId();

        String step = "exchange";
        try {
            // 5. code → 사용자 access_token (이 지역 변수에만 있다)
            String accessToken = oauth.exchange(code);

            // 6. 디스코드 사용자 id
            step = "me";
            String discordUserId = oauth.me(accessToken);

            AppUserEntity other = appUsers.findByDiscordUserId(discordUserId).orElse(null);
            if (other != null && !other.getId().equals(sessionUserId)) {
                log.warn("디스코드 연결 거부: 다른 사용자에 이미 연결된 계정 userId={} teamId={}", sessionUserId, teamId);
                return Result.DISCORD_ACCOUNT_IN_USE;
            }

            // 7. 서버에 추가 + 팀 역할. 이미 서버에 있던 사람은 roles가 적용되지 않으므로 역할을 따로 붙인다
            step = "addMember";
            if (api.addMember(discordUserId, accessToken, List.of(roleId)) == DiscordApiClient.AddMemberResult.ALREADY_MEMBER) {
                step = "addRole";
                api.addRole(discordUserId, roleId);
            }

            // 8. app_user.discord_user_id 저장. accessToken은 여기서 끝 (저장·로그 없음)
            step = "save";
            AppUserEntity user = appUsers.findById(sessionUserId).orElseThrow();
            user.linkDiscord(discordUserId);
            appUsers.saveAndFlush(user);

            log.info("디스코드 연결 완료 userId={} teamId={}", sessionUserId, teamId);
            return Result.OK;

        } catch (DataIntegrityViolationException e) {
            // 동시에 같은 디스코드 계정을 연결한 경우 유니크 제약이 막는다
            log.warn("디스코드 연결 거부: 계정 중복 userId={} teamId={}", sessionUserId, teamId);
            return Result.DISCORD_ACCOUNT_IN_USE;
        } catch (DiscordApiException e) {
            log.warn("디스코드 연결 실패 userId={} teamId={} 단계={} status={}", sessionUserId, teamId, step, e.getStatus());
            return Result.FAILED;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
