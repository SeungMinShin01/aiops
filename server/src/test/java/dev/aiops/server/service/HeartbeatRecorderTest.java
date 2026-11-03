package dev.aiops.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aiops.server.model.entity.TenantEntity;
import dev.aiops.server.model.repository.TenantRepository;
import dev.aiops.server.support.PostgresContainerSupport;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

// AI-33 : heartbeat_last 기록(touch)을 진짜 Postgres 로 검사. 테스트가 끝나면 DB 는 자동 롤백
@SpringBootTest
@Transactional
class HeartbeatRecorderTest extends PostgresContainerSupport {

    private static final String HOST = "abc123def456";

    @Autowired HeartbeatRecorder recorder;
    @Autowired TenantRepository tenants;
    @Autowired EntityManager em;

    Long tenantId;

    @BeforeEach
    void setUp() {
        tenantId = tenants.save(new TenantEntity("테스트 팀")).getId();
    }

    // 이 호스트의 heartbeat_last 행 수
    private long rowCount() {
        return ((Number) em.createNativeQuery(
                        "select count(*) from heartbeat_last where tenant_id = :t and host_hash = :h")
                .setParameter("t", tenantId)
                .setParameter("h", HOST)
                .getSingleResult()).longValue();
    }

    // 이 호스트의 started_at (비어 있으면 null)
    private Object startedAt() {
        return em.createNativeQuery(
                        "select started_at from heartbeat_last where tenant_id = :t and host_hash = :h")
                .setParameter("t", tenantId)
                .setParameter("h", HOST)
                .getSingleResult();
    }

    @Test
    void 처음_넣을_때_started가_null이면_started_at은_비어_있다() {
        recorder.touch(tenantId, "local", HOST, "sample", null);

        assertThat(rowCount()).isEqualTo(1);
        assertThat(startedAt()).isNull();
    }

    @Test
    void hello로_값이_들어온_뒤_이벤트를_받아도_started_at이_유지된다() {
        recorder.touch(tenantId, "local", HOST, "sample", Instant.parse("2026-01-01T00:00:00Z"));   // hello
        Object afterHello = startedAt();

        recorder.touch(tenantId, "local", HOST, "sample", null);   // 이벤트 수집 경로

        assertThat(startedAt()).isNotNull().isEqualTo(afterHello);
        assertThat(rowCount()).isEqualTo(1);   // 행은 하나로 합쳐짐
    }

    @Test
    void 새_hello가_오면_started_at이_새_시각으로_바뀐다() {
        recorder.touch(tenantId, "local", HOST, "sample", Instant.parse("2026-01-01T00:00:00Z"));
        Object first = startedAt();

        recorder.touch(tenantId, "local", HOST, "sample", Instant.parse("2026-02-01T00:00:00Z"));

        assertThat(startedAt()).isNotEqualTo(first);
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    void hostHash가_없으면_기록하지_않는다() {
        recorder.touch(tenantId, "local", null, "sample", null);
        recorder.touch(tenantId, "local", "  ", "sample", null);

        assertThat(rowCount()).isZero();
    }
}