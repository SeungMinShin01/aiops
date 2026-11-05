package dev.aiops.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import dev.aiops.protocol.AppInfo;
import dev.aiops.protocol.ErrorEvent;
import dev.aiops.protocol.EventBatch;
import dev.aiops.protocol.Phase;
import dev.aiops.server.model.entity.TenantEntity;
import dev.aiops.server.model.repository.IngestStatRepository;
import dev.aiops.server.model.repository.TenantRepository;
import dev.aiops.server.security.TenantContext;
import dev.aiops.server.support.PostgresContainerSupport;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

// AI-62 : 저장 트랜잭션이 실패해 취소되면 통계도 같이 취소되는지 확인
// 진짜로 커밋·롤백을 봐야 해서 @Transactional 을 붙이지 않음
@SpringBootTest
class IngestStatRollbackTest extends PostgresContainerSupport {

    @Autowired IngestService ingestService;
    @Autowired IngestStatRepository stats;
    @Autowired TenantRepository tenants;
    @MockitoBean HeartbeatRecorder heartbeatRecorder;   // 가짜: 통계를 쓴 "다음 단계"에서 일부러 실패시킴

    @Test
    void 저장이_실패하면_통계도_같이_취소된다() {
        Long team = tenants.save(new TenantEntity("rb-" + UUID.randomUUID().toString().substring(0, 8))).getId();
        doThrow(new RuntimeException("boom")).when(heartbeatRecorder).touch(any(), any(), any(), any(), any());

        ErrorEvent event = new ErrorEvent(1700000000000L, "ERROR", "java.lang.NullPointerException", "boom",
                List.of(), List.of(), "main", Phase.RUNTIME);
        EventBatch batch = new EventBatch("local", new AppInfo("sample", "abc", false, "abc123def456"),
                List.of(event), 4);

        // 이벤트 저장 → 통계 기록 → 하트비트에서 실패 → 전체 취소
        // AI-16 병합: store 는 팀을 TenantContext 에서 꺼낸다 (요청 스레드에서 키 필터가 하는 일을 흉내)
        TenantContext.set(team);
        try {
            assertThatThrownBy(() -> ingestService.store("local", batch)).isInstanceOf(RuntimeException.class);
        } finally {
            TenantContext.clear();
        }

        // 확인은 owner(RLS 무시)로: 팀 설정 없이 app 계정으로 읽으면 RLS 때문에 항상 0건이라 검사가 무의미해진다
        JdbcTemplate owner = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), OWNER_USER, OWNER_PASSWORD));
        assertThat(owner.queryForObject("SELECT count(*) FROM ingest_stat WHERE tenant_id = ?", Long.class, team))
                .isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM error_event WHERE tenant_id = ?", Long.class, team))
                .as("이벤트도 같이 취소").isZero();
    }
}