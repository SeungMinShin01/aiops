package dev.aiops.server.service;

import dev.aiops.server.dto.ErrorGroupFilter;
import dev.aiops.server.dto.ErrorGroupSummary;
import dev.aiops.server.model.entity.ErrorGroupEntity;
import dev.aiops.server.model.repository.ErrorGroupQueryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// AI-95 : 콘솔 에러 목록 (그룹 단위). 팀은 MembershipGuard 가 정한 TenantContext 로 이미 정해져 있고,
// 이 트랜잭션이 열릴 때 set_config 가 불려서 RLS 가 그 팀 행만 거른다. 여기서 팀 id 를 따로 받지 않는다.
@Service
public class ErrorGroupQueryService {

    private final ErrorGroupQueryRepository queryRepository;

    public ErrorGroupQueryService(ErrorGroupQueryRepository queryRepository) {
        this.queryRepository = queryRepository;
    }

    // 조건·정렬·페이지로 한 쪽만 DB 에서 가져와 요약으로 바꾼다 (전부 읽어서 자바에서 거르지 않음)
    @Transactional(readOnly = true)
    public Page<ErrorGroupSummary> list(ErrorGroupFilter filter, Pageable pageable) {
        return queryRepository.search(filter, pageable).map(ErrorGroupQueryService::toSummary);
    }

    private static ErrorGroupSummary toSummary(ErrorGroupEntity group) {
        return new ErrorGroupSummary(group.getId(), group.getExceptionType(), group.getTopFrame(),
                group.getFirstEnv(), group.getStatus(), group.getOccurrenceCount(),
                group.getFirstSeenAt(), group.getLastSeenAt(), group.getAiExplanation() != null);
    }
}