package dev.aiops.server.service;

import dev.aiops.server.dto.AcceptResponse;
import dev.aiops.server.dto.InviteSummary;
import dev.aiops.server.model.entity.InvitationEntity;
import dev.aiops.server.model.entity.MemberRole;
import dev.aiops.server.model.entity.MembershipEntity;
import dev.aiops.server.model.entity.TenantEntity;
import dev.aiops.server.model.repository.InvitationRepository;
import dev.aiops.server.model.repository.MembershipRepository;
import dev.aiops.server.model.repository.TenantRepository;
import dev.aiops.server.security.ApiKeyHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * 초대 링크 (14단계). 발급(issue)과 수락(accept)을 이 서비스 하나가 맡는다.
 * - 조장 링크: 13단계 관리자(A, AI-84)가 issue(teamId, OWNER, 1, 7일, adminId)를 부른다
 * - 팀원 링크: AI-89 (조장이 발급)
 * - 수락: AI-105
 * <p>
 * 토큰 형식 (A와 합의): 원문 = base64url(SecureRandom 32바이트, 패딩 없음, 43자),
 * DB에는 ApiKeyHasher.sha256Hex(원문)만, 링크 = &lt;aiops.console.base-url&gt;/invite/&lt;원문&gt;.
 * 원문은 발급 응답에 1회, 수락 요청 경로에만 있다. DB·로그에 남기지 않는다 (규칙 10).
 * invitation·membership·tenant는 RLS가 없다 (D-023).
 */
@Service
public class InvitationService {

    private static final Logger log = LoggerFactory.getLogger(InvitationService.class);

    static final String NEXT_OWNER = "key-guide";   // D-092: 키는 관리자가 디스코드 DM으로
    static final String NEXT_MEMBER = "errors";

    private static final SecureRandom RANDOM = new SecureRandom();   // getInstanceStrong()은 리눅스에서 멈출 수 있어 쓰지 않는다
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final InvitationRepository invitations;
    private final MembershipRepository memberships;
    private final TenantRepository tenants;
    private final String consoleBaseUrl;
    private final Duration memberTtl;

    public InvitationService(InvitationRepository invitations, MembershipRepository memberships,
            TenantRepository tenants, @Value("${aiops.console.base-url:}") String consoleBaseUrl,
            @Value("${aiops.invite.member-ttl:7d}") Duration memberTtl) {
        this.invitations = invitations;
        this.memberships = memberships;
        this.tenants = tenants;
        this.consoleBaseUrl = consoleBaseUrl == null ? "" : consoleBaseUrl.replaceAll("/+$", "");
        this.memberTtl = memberTtl;
    }

    /** 발급 결과. rawToken·inviteUrl은 이 응답에서 1회만 쓴다 (저장·로그 금지) */
    public record Issued(long invitationId, String rawToken, String inviteUrl, Instant expiresAt, int maxUses) {
        @Override
        public String toString() {   // 실수로 로그에 찍혀도 원문이 나가지 않게
            return "Issued[invitationId=" + invitationId + ", expiresAt=" + expiresAt + ", maxUses=" + maxUses + "]";
        }
    }

    /**
     * 초대 링크 발급. 권한 판단(관리자·조장)은 부르는 쪽(컨트롤러·가드)이 한다.
     *
     * @param ttl       유효 기간 (조장 aiops.invite.owner-ttl, 팀원 aiops.invite.member-ttl)
     * @param createdBy 발급한 사람 app_user.id
     */
    @Transactional
    public Issued issue(Long tenantId, MemberRole role, int maxUses, Duration ttl, Long createdBy) {
        if (maxUses < 1) {
            throw new IllegalArgumentException("maxUses must be >= 1");
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = TOKEN_ENCODER.encodeToString(bytes);

        Instant expiresAt = Instant.now().plus(ttl);
        InvitationEntity saved = invitations.save(
                new InvitationEntity(tenantId, ApiKeyHasher.sha256Hex(raw), role, maxUses, expiresAt, createdBy));
        log.info("초대 발급 teamId={} role={} maxUses={} by={}", tenantId, role, maxUses, createdBy);   // 원문 없음
        return new Issued(saved.getId(), raw, inviteUrl(raw), expiresAt, maxUses);
    }

    /** 팀원 링크 발급 (AI-89, 조장). 여러 명 사용, 만료는 aiops.invite.member-ttl (7일) */
    @Transactional
    public Issued issueMemberInvite(Long tenantId, int maxUses, Long byUserId) {
        return issue(tenantId, MemberRole.MEMBER, maxUses, memberTtl, byUserId);
    }

    /** 이 팀의 아직 유효한 링크 (원문·해시 없음, AI-89) */
    @Transactional(readOnly = true)
    public List<InviteSummary> listActive(Long tenantId) {
        return invitations.findAllByTenantIdAndExpiresAtAfterOrderByCreatedAtDesc(tenantId, Instant.now()).stream()
                .map(i -> new InviteSummary(i.getId(), i.getRole().name(), i.getUsedCount(), i.getMaxUses(),
                        i.getExpiresAt(), i.getCreatedAt()))
                .toList();
    }

    /**
     * 링크 무효화 (AI-89). 지우지 않고 만료 시각을 지금으로 — 누가 언제 만든 링크였는지 기록은 남긴다.
     *
     * @return 이 팀의 링크가 있었으면 true (다른 팀 링크 id면 false → 404)
     */
    @Transactional
    public boolean expire(Long tenantId, Long invitationId) {
        return invitations.findByIdAndTenantId(invitationId, tenantId)
                .map(i -> {
                    i.expireNow();
                    invitations.save(i);
                    return true;
                })
                .orElse(false);
    }

    /** 콘솔 초대 화면 주소 */
    public String inviteUrl(String rawToken) {
        return consoleBaseUrl + "/invite/" + rawToken;
    }

    /**
     * 초대 수락. 사용 횟수 +1, 멤버 등록, (조장 링크면) 팀 ACTIVE를 한 트랜잭션으로.
     *
     * @throws InvitationNotFoundException 없는 토큰 (404)
     * @throws InvitationGoneException     만료·횟수 초과 (410)
     */
    @Transactional
    public AcceptResponse accept(String rawToken, Long userId) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new InvitationNotFoundException();
        }
        InvitationEntity invitation = invitations.findByTokenHash(ApiKeyHasher.sha256Hex(rawToken))
                .orElseThrow(InvitationNotFoundException::new);

        if (!invitation.getExpiresAt().isAfter(Instant.now())) {
            throw new InvitationGoneException("expired");
        }
        if (invitation.getUsedCount() >= invitation.getMaxUses()) {
            throw new InvitationGoneException("used up");
        }

        Long teamId = invitation.getTenantId();
        TenantEntity tenant = tenants.findById(teamId).orElseThrow(InvitationNotFoundException::new);

        // 이미 그 팀 멤버면 그대로 끝 (멤버 중복 생성·횟수 증가 없음). 역할은 지금 가진 역할 기준
        MembershipEntity existing = memberships.findByUserIdAndTenantId(userId, teamId).orElse(null);
        if (existing != null) {
            return response(tenant, existing.getRole());
        }

        // 동시 수락 방지: 조건부 UPDATE가 0행이면 그 사이 다 썼거나 만료됨
        if (invitations.incrementUsedCount(invitation.getId()) == 0) {
            throw new InvitationGoneException("used up");
        }
        memberships.save(new MembershipEntity(userId, teamId, invitation.getRole()));

        if (invitation.getRole() == MemberRole.OWNER) {
            tenant.activateByOwner();   // WAITING_OWNER → ACTIVE (정지된 팀은 그대로)
            tenants.save(tenant);
        }

        log.info("초대 수락 userId={} teamId={} role={}", userId, teamId, invitation.getRole()); // 토큰은 남기지 않는다
        return response(tenant, invitation.getRole());
    }

    private static AcceptResponse response(TenantEntity tenant, MemberRole role) {
        return new AcceptResponse(tenant.getId(), tenant.getName(), role.name(),
                role == MemberRole.OWNER ? NEXT_OWNER : NEXT_MEMBER);
    }
}
