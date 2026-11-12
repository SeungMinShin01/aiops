package dev.aiops.server.service;

import dev.aiops.server.dto.BulkCreateRequest.TeamInput;
import dev.aiops.server.dto.TeamRow;
import dev.aiops.server.model.entity.MemberRole;
import dev.aiops.server.model.entity.MembershipEntity;
import dev.aiops.server.model.entity.TenantEntity;
import dev.aiops.server.model.repository.AppUserRepository;
import dev.aiops.server.model.repository.CohortRepository;
import dev.aiops.server.model.repository.MembershipRepository;
import dev.aiops.server.model.repository.TenantRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

// AI-83 : 관리자가 기수 안에 팀을 한꺼번에 만든다. 팀 상태는 WAITING_OWNER 로 시작 (D-071)
// 팀 생성 직후 TeamCreated 이벤트만 발행한다. 디스코드 채널 만들기는 DiscordProvisionListener 가 커밋 뒤에 따로 처리 (AI-92)
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

    public AdminTeamService(CohortRepository cohorts, TenantRepository tenants, MembershipRepository memberships,
                            AppUserRepository appUsers, ApplicationEventPublisher events,
                            PlatformTransactionManager transactionManager) {
        this.cohorts = cohorts;
        this.tenants = tenants;
        this.memberships = memberships;
        this.appUsers = appUsers;
        this.events = events;
        this.oneTeamTx = new TransactionTemplate(transactionManager);
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