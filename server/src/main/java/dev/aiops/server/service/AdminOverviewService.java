package dev.aiops.server.service;

import dev.aiops.server.dto.OverviewRow;
import dev.aiops.server.model.entity.CohortEntity;
import dev.aiops.server.model.entity.TenantEntity;
import dev.aiops.server.model.repository.CohortRepository;
import dev.aiops.server.model.repository.MembershipRepository;
import dev.aiops.server.model.repository.TenantRepository;
import dev.aiops.server.model.repository.TenantStatsQuery;
import dev.aiops.server.model.repository.TenantStatsQuery.TenantStats;
import dev.aiops.server.security.TenantContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

// AI-86 : 관리자 전체 현황. 팀마다 TenantContext 를 바꿔가며 그 팀의 수치만 읽어 합친다 (D-082)
// RLS 를 우회하지 않는다: BYPASSRLS 계정·SECURITY DEFINER 뷰·SET ROLE 모두 쓰지 않는다.
// 팀 수가 수십 개 이하라 팀마다 짧은 트랜잭션(N회 조회)이면 충분하다. 캐시·뷰는 만들지 않는다 (MVP)
// 이 메서드 자체는 @Transactional 이 아니다. 팀 설정이 있는 트랜잭션은 TenantStatsQuery.collect 안에서만 열린다
@Service
public class AdminOverviewService {

    private static final Logger log = LoggerFactory.getLogger(AdminOverviewService.class);

    private final TenantRepository tenants;
    private final MembershipRepository memberships;
    private final CohortRepository cohorts;
    private final TenantStatsQuery statsQuery;

    public AdminOverviewService(TenantRepository tenants, MembershipRepository memberships,
                                CohortRepository cohorts, TenantStatsQuery statsQuery) {
        this.tenants = tenants;
        this.memberships = memberships;
        this.cohorts = cohorts;
        this.statsQuery = statsQuery;
    }

    // AI-86 : cohortId 가 null 이면 전체 팀, 있으면 그 기수의 팀. 팀 번호 순
    public List<OverviewRow> overview(Long cohortId) {
        // 1. 팀 목록 (tenant 는 RLS 없음)
        List<TenantEntity> teams = (cohortId == null ? tenants.findAll() : tenants.findAllByCohortId(cohortId))
                .stream()
                .sorted(Comparator.comparing(TenantEntity::getId))
                .toList();

        // 2. 조장 로그인 이름 (membership·app_user 는 RLS 없음, 한 번에)
        Map<Long, String> owners = memberships.findOwnersByTenantIds(teams.stream().map(TenantEntity::getId).toList());

        // 3. 기수 이름 (한 번에)
        Set<Long> cohortIds = new HashSet<>();
        for (TenantEntity team : teams) {
            if (team.getCohortId() != null) {
                cohortIds.add(team.getCohortId());
            }
        }
        Map<Long, String> cohortNames = cohorts.findAllById(cohortIds).stream()
                .collect(Collectors.toMap(CohortEntity::getId, CohortEntity::getName, (a, b) -> a));

        // 4. 팀마다: TenantContext 설정 → 짧은 트랜잭션으로 수치 → 반드시 clear. 한 팀이 실패해도 다음 팀은 계속 (그 팀은 수치 null)
        List<OverviewRow> rows = new ArrayList<>();
        for (TenantEntity team : teams) {
            TenantStats stats = null;
            try {
                TenantContext.set(team.getId());
                stats = statsQuery.collect();
            } catch (RuntimeException e) {
                // 원인 메시지에 값이 섞일 수 있어 클래스 이름만 남긴다
                log.warn("현황 집계 실패 teamId={} 원인={}", team.getId(), e.getClass().getSimpleName());
            } finally {
                TenantContext.clear();
            }
            rows.add(new OverviewRow(team.getId(), team.getName(), cohortNames.get(team.getCohortId()),
                    team.getStatus(), owners.get(team.getId()),
                    stats == null ? null : stats.lastSeenLocal(),
                    stats == null ? null : stats.lastSeenProd(),
                    stats == null ? null : stats.errors24h(),
                    team.getDiscordStatus()));
        }
        return rows;
    }
}