package dev.aiops.server.service;

import dev.aiops.server.dto.BulkCreateRequest.TeamInput;
import dev.aiops.server.dto.OwnerInviteResponse;
import dev.aiops.server.dto.OwnerInviteStatus;
import dev.aiops.server.dto.TeamRow;
import dev.aiops.server.model.entity.InvitationEntity;
import dev.aiops.server.model.entity.MemberRole;
import dev.aiops.server.model.entity.MembershipEntity;
import dev.aiops.server.model.entity.TenantEntity;
import dev.aiops.server.model.entity.TenantStatus;
import dev.aiops.server.model.repository.AppUserRepository;
import dev.aiops.server.model.repository.CohortRepository;
import dev.aiops.server.model.repository.InvitationRepository;
import dev.aiops.server.model.repository.MembershipRepository;
import dev.aiops.server.model.repository.TenantRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

// AI-83 : 관리자가 기수 안에 팀을 한꺼번에 만든다. 팀 상태는 WAITING_OWNER 로 시작 (D-071)
// 팀 생성 직후 TeamCreated 이벤트만 발행한다. 디스코드 채널 만들기는 DiscordProvisionListener 가 커밋 뒤에 따로 처리 (AI-92)
// AI-84 : 팀마다 1회용 조장 초대 링크 발급·조회. 토큰 만들기·해시·저장은 InvitationService.issue 가 한다
@Service
public class AdminTeamService {

    private static final Logger log = LoggerFactory.getLogger(AdminTeamService.class);

    // AI-83 : 한 번에 만들 수 있는 팀 수 상한. 넘으면 400
    static final int MAX_TEAMS = 50;

    private final CohortRepository cohorts;
    private final TenantRepository tenants;
    private final MembershipRepository memberships;
    private final AppUserRepository appUsers;
    private final ApplicationEventPublisher events;
    // AI-83 : 팀 1개를 저장하는 짧은 트랜잭션. 전체 묶음을 한 트랜잭션으로 묶지 않기 위해 직접 연다
    private final TransactionTemplate oneTeamTx;
    // AI-84 : 조장 링크 발급·기존 링크 무효화에 쓴다
    private final InvitationService invitationService;
    private final InvitationRepository invitations;
    private final Duration ownerTtl;

    public AdminTeamService(CohortRepository cohorts, TenantRepository tenants, MembershipRepository memberships,
                            AppUserRepository appUsers, ApplicationEventPublisher events,
                            PlatformTransactionManager transactionManager,
                            InvitationService invitationService, InvitationRepository invitations,
                            @Value("${aiops.invite.owner-ttl:7d}") Duration ownerTtl) {
        this.cohorts = cohorts;
        this.tenants = tenants;
        this.memberships = memberships;
        this.appUsers = appUsers;
        this.events = events;
        this.oneTeamTx = new TransactionTemplate(transactionManager);
        this.invitationService = invitationService;
        this.invitations = invitations;
        this.ownerTtl = ownerTtl;
    }

    // AI-83 : 검증 → 팀마다 (저장 + 커밋) → 결과 모음. 이 메서드 자체는 @Transactional 이 아님
    public List<TeamRow> bulkCreate(Long cohortId, List<TeamInput> teams, long adminUserId) {
        // 1. 기수가 없으면 404
        if (cohortId == null || !cohorts.existsById(cohortId)) {
            throw new AdminApiException(HttpStatus.NOT_FOUND, "COHORT_NOT_FOUND", "기수를 찾을 수 없습니다");
        }
        // 2. 팀이 비었거나 50개 초과면 400
        if (teams == null || teams.isEmpty() || teams.size() > MAX_TEAMS) {
            throw new AdminApiException(HttpStatus.BAD_REQUEST, "TEAMS_COUNT_INVALID",
                    "팀은 1개 이상 " + MAX_TEAMS + "개 이하로 보내야 합니다");
        }
        // 3. 이름 검사: 비어 있으면 400, 요청 안에서 겹치거나 같은 기수에 이미 있으면 409. 하나라도 걸리면 아무것도 만들지 않음
        List<String> names = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (TeamInput team : teams) {
            String name = team == null || team.name() == null ? "" : team.name().trim();
            if (name.isEmpty()) {
                throw new AdminApiException(HttpStatus.BAD_REQUEST, "TEAM_NAME_REQUIRED", "팀 이름이 필요합니다");
            }
            if (!seen.add(name) || tenants.existsByCohortIdAndName(cohortId, name)) {
                throw new AdminApiException(HttpStatus.CONFLICT, "TEAM_NAME_DUPLICATE", "이미 있는 팀 이름입니다: " + name);
            }
            names.add(name);
        }

        // 4. 팀마다 짧은 트랜잭션: 저장 → TeamCreated 발행 → 커밋. 하나가 실패해도 나머지는 계속 만든다
        List<TeamRow> rows = new ArrayList<>();
        for (int i = 0; i < teams.size(); i++) {
            String name = names.get(i);
            LocalDate projectEndDate = teams.get(i).projectEndDate();
            try {
                TenantEntity saved = oneTeamTx.execute(status -> {
                    TenantEntity tenant = tenants.save(new TenantEntity(name, cohortId, projectEndDate));
                    // 커밋이 확정된 뒤에만 디스코드 리스너가 받는다 (트랜잭션 밖에서 외부 호출)
                    events.publishEvent(new TeamCreated(tenant.getId()));
                    return tenant;
                });
                rows.add(toRow(saved, null));
            } catch (RuntimeException e) {
                // 이름은 로그에 남겨도 되지만 원인 메시지는 클래스 이름만 (DB 메시지에 값이 섞일 수 있음)
                log.warn("팀 생성 실패 cohortId={} name={} 원인={}", cohortId, name, e.getClass().getSimpleName());
            }
        }
        log.info("관리자 팀 일괄 생성 adminUserId={} cohortId={} 요청={} 생성={}", adminUserId, cohortId, teams.size(), rows.size());
        return rows;
    }

    // AI-83 : 기수의 팀 목록 + 조장 로그인 이름 (조장이 없으면 null). 초대 링크 발급 대상을 고를 때 씀
    @Transactional(readOnly = true)
    public List<TeamRow> listByCohort(Long cohortId) {
        return tenants.findAllByCohortId(cohortId).stream()
                .sorted(Comparator.comparing(TenantEntity::getId))
                .map(t -> toRow(t, ownerLogin(t.getId())))
                .toList();
    }

    // AI-84 : 조장 초대 링크 발급. 상태 확인 → 이전 링크 무효화 → 새 링크 발급을 한 트랜잭션으로
    // 중간에 실패하면 이전 링크도 그대로 살아 있다 (링크가 없어지는 일이 없음)
    @Transactional
    public OwnerInviteResponse issueOwnerInvite(Long teamId, long adminUserId) {
        // 1. 팀이 없으면 404, 조장 대기 상태가 아니면 409 (이미 조장이 있거나 정지된 팀에는 만들지 않음)
        TenantEntity team = tenants.findById(teamId).orElseThrow(this::teamNotFound);
        if (team.getStatus() != TenantStatus.WAITING_OWNER) {
            throw new AdminApiException(HttpStatus.CONFLICT, "TEAM_ALREADY_HAS_OWNER",
                    "이미 조장이 있거나 정지된 팀입니다");
        }
        // 2. 같은 팀의 미사용·미만료 OWNER 링크는 지금 만료시켜서 링크가 팀당 하나만 유효하게 한다
        for (InvitationEntity old : invitations.findAllByTenantIdAndExpiresAtAfterOrderByCreatedAtDesc(teamId, Instant.now())) {
            if (old.getRole() == MemberRole.OWNER && old.getUsedCount() < old.getMaxUses()) {
                old.expireNow();
                invitations.save(old);
            }
        }
        // 3. 새 링크: OWNER, 1회용, 7일. 토큰 원문은 이 응답에만 담고 어디에도 저장·로그하지 않는다
        InvitationService.Issued issued = invitationService.issue(teamId, MemberRole.OWNER, 1, ownerTtl, adminUserId);
        return new OwnerInviteResponse(issued.inviteUrl(), issued.expiresAt());
    }

    // AI-84 : 지금 유효한 조장 링크의 { expiresAt, used }. 원문은 DB에 없으므로 줄 수 없다 (잃어버리면 POST 로 재발급)
    @Transactional(readOnly = true)
    public OwnerInviteStatus ownerInviteStatus(Long teamId) {
        tenants.findById(teamId).orElseThrow(this::teamNotFound);
        return invitations.findAllByTenantIdAndExpiresAtAfterOrderByCreatedAtDesc(teamId, Instant.now()).stream()
                .filter(i -> i.getRole() == MemberRole.OWNER)
                .findFirst()
                .map(i -> new OwnerInviteStatus(i.getExpiresAt(), i.getUsedCount() >= i.getMaxUses()))
                .orElseThrow(() -> new AdminApiException(HttpStatus.NOT_FOUND, "OWNER_INVITE_NOT_FOUND",
                        "유효한 조장 링크가 없습니다"));
    }

    private AdminApiException teamNotFound() {
        return new AdminApiException(HttpStatus.NOT_FOUND, "TEAM_NOT_FOUND", "팀을 찾을 수 없습니다");
    }

    // AI-83 : 팀의 OWNER 멤버 → 로그인 이름. 팀이 수십 개라 팀마다 조회해도 충분
    private String ownerLogin(Long tenantId) {
        return memberships.findAllByTenantId(tenantId).stream()
                .filter(m -> m.getRole() == MemberRole.OWNER)
                .map(MembershipEntity::getUserId)
                .findFirst()
                .flatMap(appUsers::findById)
                .map(u -> u.getLogin())
                .orElse(null);
    }

    private static TeamRow toRow(TenantEntity t, String ownerLogin) {
        return new TeamRow(t.getId(), t.getName(), t.getStatus(), t.getDiscordStatus(), t.getProjectEndDate(), ownerLogin);
    }
}