package dev.aiops.server.service;

import dev.aiops.server.model.entity.AppUserEntity;
import dev.aiops.server.model.entity.DiscordStatus;
import dev.aiops.server.model.entity.MemberRole;
import dev.aiops.server.model.entity.MembershipEntity;
import dev.aiops.server.model.entity.TenantEntity;
import dev.aiops.server.model.repository.AppUserRepository;
import dev.aiops.server.model.repository.MembershipRepository;
import dev.aiops.server.model.repository.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 로그인 사용자 (AI-78). app_user·membership·tenant는 RLS가 없어 팀 설정 없이 읽는다 (D-023).
 */
@Service
public class AppUserService {

    private final AppUserRepository users;
    private final MembershipRepository memberships;
    private final TenantRepository tenants;

    public AppUserService(AppUserRepository users, MembershipRepository memberships, TenantRepository tenants) {
        this.users = users;
        this.memberships = memberships;
        this.tenants = tenants;
    }

    /** 첫 로그인이면 만들고, 있으면 login·name만 갱신한다. 팀을 자동 생성·가입시키지 않는다 (팀 합류는 초대로만) */
    @Transactional
    public AppUserEntity upsertFromGithub(long githubId, String login, String name) {
        return users.findByGithubId(githubId)
                .map(user -> {
                    user.updateProfile(login, name);
                    return user;
                })
                .orElseGet(() -> users.save(new AppUserEntity(githubId, login, name)));
    }

    @Transactional(readOnly = true)
    public Optional<AppUserEntity> find(long appUserId) {
        return users.findById(appUserId);
    }

    /** 내가 속한 팀 (팀 번호 순). 표시용. 권한 판단은 MembershipGuard가 요청마다 한다 */
    @Transactional(readOnly = true)
    public List<MyTeam> teamsOf(long appUserId) {
        List<MembershipEntity> mine = memberships.findAllByUserId(appUserId).stream()
                .sorted(java.util.Comparator.comparing(MembershipEntity::getTenantId))
                .toList();
        Map<Long, TenantEntity> teams = tenants.findAllById(mine.stream().map(MembershipEntity::getTenantId).toList())
                .stream()
                .collect(Collectors.toMap(TenantEntity::getId, t -> t, (a, b) -> a));
        return mine.stream()
                .map(m -> {
                    TenantEntity t = teams.get(m.getTenantId());
                    return new MyTeam(m.getTenantId(), t == null ? null : t.getName(), m.getRole(),
                            t == null ? null : t.getDiscordStatus());
                })
                .toList();
    }

    /** discordStatus: 그 팀 디스코드 채널 준비 상태 (AI-106, D-110). 채널 번호·웹훅 주소는 넣지 않는다 */
    public record MyTeam(long teamId, String name, MemberRole role, DiscordStatus discordStatus) {
    }
}
