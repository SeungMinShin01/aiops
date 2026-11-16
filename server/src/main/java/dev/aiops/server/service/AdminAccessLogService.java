package dev.aiops.server.service;

import dev.aiops.server.dto.AccessLogRow;
import dev.aiops.server.model.entity.AdminAccessLogEntity;
import dev.aiops.server.model.repository.AdminAccessLogRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// AI-87 : 관리자 접근 기록 저장·조회
@Service
public class AdminAccessLogService {

    static final int PAGE_SIZE = 200;   // 한 번에 최대 200건
    private static final Instant MIN = Instant.EPOCH;
    private static final Instant MAX = Instant.parse("3000-01-01T00:00:00Z");   // "끝 날짜 없음"의 대용 (Postgres timestamptz 범위 안)

    private final AdminAccessLogRepository repository;

    public AdminAccessLogService(AdminAccessLogRepository repository) {
        this.repository = repository;
    }

    // AI-87 : 기록 1행 저장. REQUIRES_NEW = 본 요청 트랜잭션과 따로 커밋 → 본 요청이 롤백돼도 기록은 남음
    // 비동기(@Async)로 빼지 않음 (유실 가능성). 호출하는 인터셉터가 예외를 잡아 로그만 남김
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long adminUserId, Long tenantId, String action) {
        repository.save(new AdminAccessLogEntity(adminUserId, tenantId, action));
    }

    // AI-87 : 최근 것부터 200건씩. from 이상·to 미만. 값이 없으면 전체 기간, adminUserId 가 없으면 모든 관리자
    @Transactional(readOnly = true)
    public List<AccessLogRow> page(Instant from, Instant to, Long adminUserId, int page) {
        Instant start = from == null ? MIN : from;
        Instant end = to == null ? MAX : to;
        PageRequest pageable = PageRequest.of(Math.max(page, 0), PAGE_SIZE);

        List<AdminAccessLogEntity> rows = adminUserId == null
                ? repository.findInRange(start, end, pageable)
                : repository.findInRangeByAdmin(adminUserId, start, end, pageable);
        return rows.stream()
                .map(r -> new AccessLogRow(r.getId(), r.getAdminUserId(), r.getTenantId(), r.getAction(), r.getAt()))
                .toList();
    }
}