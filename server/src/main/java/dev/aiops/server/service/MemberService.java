package dev.aiops.server.service;

import dev.aiops.server.dto.MemberSummary;
import dev.aiops.server.model.entity.AppUserEntity;
import dev.aiops.server.model.entity.MembershipEntity;
import dev.aiops.server.model.repository.AppUserRepository;
import dev.aiops.server.model.repository.MembershipRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 팀원 목록·내보내기 (AI-89). membership·app_user는 RLS가 없어서 모든 조회에 tenant_id 조건을 고정한다 (D-023).
 * 권한(조장만 내보내기)은 컨트롤러의 @RequireOwner가 판단한다.
 */
@Service
public class MemberService {

    private static final Logger log = LoggerFactory.getLogger(MemberService.class);

    private final MembershipRepository memberships;
    private final AppUserRepository users;
    private final DiscordProvisioner discordProvisioner;

    public MemberService(MembershipRepository memberships, AppUserRepository users,
            DiscordProvisioner discordProvisioner) {
        this.memberships = memberships;
        this.users = users;
        this.discordProvisioner = discordProvisioner;
    }

    /** 팀원 목록 (가입 순) */
    @Transactional(readOnly = true)
    public List<MemberSummary> list(Long teamId) {
        List<MembershipEntity> members = memberships.findAllByTenantId(teamId);
        Map<Long, AppUserEntity> byId = users.findAllById(members.stream().map(MembershipEntity::getUserId).toList())
                .stream().collect(Collectors.toMap(AppUserEntity::getId, Function.identity()));
        return members.stream()
                .sorted(Comparator.comparing(MembershipEntity::getCreatedAt).thenComparing(MembershipEntity::getId))
                .map(m -> {
                    AppUserEntity u = byId.get(m.getUserId());
                    return new MemberSummary(m.getUserId(), u == null ? null : u.getLogin(),
                            u == null ? null : u.getName(), m.getRole().name(), m.getCreatedAt());
                })
                .toList();
    }

    /**
     * 팀원 내보내기. 삭제는 짧은 트랜잭션으로 바로 커밋되고(그 사람의 다음 요청부터 403),
     * 디스코드 역할 회수는 그 뒤에 트랜잭션 밖에서 부른다 (실패해도 내보내기는 성공, 예외 없음).
     *
     * @throws CannotRemoveSelfException 조장이 자기 자신을 내보내려 함 (400). 조장 교체는 MVP 범위 밖
     * @throws MemberNotFoundException   그 팀 멤버가 아님 (404)
     */
    public void remove(Long teamId, Long userId, Long byUserId) {
        if (userId.equals(byUserId)) {
            throw new CannotRemoveSelfException();
        }
        if (memberships.deleteByUserIdAndTenantId(userId, teamId) == 0) {
            throw new MemberNotFoundException();
        }
        log.info("팀원 내보내기 teamId={} userId={} by={}", teamId, userId, byUserId);
        discordProvisioner.revokeRole(teamId, userId);   // AI-93: 디스코드 팀 역할 회수 (서버에서 쫓아내지는 않음)
    }

    /** 조장 자신은 내보낼 수 없다 → 400 */
    public static class CannotRemoveSelfException extends RuntimeException {
        public CannotRemoveSelfException() {
            super("cannot remove self");
        }
    }

    /** 그 팀 멤버가 아님 → 404 */
    public static class MemberNotFoundException extends RuntimeException {
        public MemberNotFoundException() {
            super("member not found");
        }
    }
}
