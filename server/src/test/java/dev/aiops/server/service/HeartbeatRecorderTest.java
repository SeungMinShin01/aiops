package dev.aiops.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aiops.server.model.entity.TenantEntity;
import dev.aiops.server.model.repository.TenantRepository;
import dev.aiops.server.security.TenantContext;
import dev.aiops.server.support.PostgresContainerSupport;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// AI-33 : heartbeat_last 기록(touch)을 진짜 Postgres 로 검사.
// V2(RLS) 이후 (AI-16에서 수정): heartbeat_last 는 팀 테이블이라
// - 클래스 @Transactional 을 두지 않는다 (트랜잭션이 먼저 열리면 set_config 가 안 들어가 RLS 에 막힘)
// - TenantContext.set 뒤 TransactionTemplate 안에서 touch (실제로는 IngestService·HelloService 의 트랜잭션 안에서 불림)
// - 롤백이 없으므로 시작할 때 owner(RLS 무시)로 비우고, 확인도 owner 로 읽는다
@SpringBootTest
class HeartbeatRecorderTest extends PostgresContainerSupport {

    private static final String HOST = "abc123def456";

    @Autowired HeartbeatRecorder recorder;
    @Autowired TenantRepository tenants;
    @Autowired PlatformTransactionManager txManager;

    JdbcTemplate owner;
    TransactionTemplate tx;
    Long tenantId;

    @BeforeEach
    void setUp() {
        owner = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), OWNER_USER, OWNER_PASSWORD));
        owner.execute("TRUNCATE error_event, error_group, app_snapshot, ingest_stat, heartbeat_last, api_key, tenant RESTART IDENTITY CASCADE");
        tx = new TransactionTemplate(txManager);
        tenantId = tenants.save(new TenantEntity("테스트 팀")).getId();
        TenantContext.set(tenantId);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private void touch(String host, Instant started) {
        tx.executeWithoutResult(s -> recorder.touch(tenantId, "local", host, "sample", started));
    }

    // 이 호스트의 heartbeat_last 행 수
    private long rowCount() {
        return owner.queryForObject(
                "select count(*) from heartbeat_last where tenant_id = ? and host_hash = ?", Long.class, tenantId, HOST);
    }

    // 이 호스트의 started_at (비어 있으면 null)
    private Timestamp startedAt() {
        return owner.queryForObject(
                "select started_at from heartbeat_last where tenant_id = ? and host_hash = ?", Timestamp.class, tenantId, HOST);
    }

    @Test
    void 처음_넣을_때_started가_null이면_started_at은_비어_있다() {
        touch(HOST, null);

        assertThat(rowCount()).isEqualTo(1);
        assertThat(startedAt()).isNull();
    }

    @Test
    void hello로_값이_들어온_뒤_이벤트를_받아도_started_at이_유지된다() {
        touch(HOST, Instant.parse("2026-01-01T00:00:00Z"));   // hello
        Timestamp afterHello = startedAt();

        touch(HOST, null);   // 이벤트 수집 경로

        assertThat(startedAt()).isNotNull().isEqualTo(afterHello);
        assertThat(rowCount()).isEqualTo(1);   // 행은 하나로 합쳐짐
    }

    @Test
    void 새_hello가_오면_started_at이_새_시각으로_바뀐다() {
        touch(HOST, Instant.parse("2026-01-01T00:00:00Z"));
        Timestamp first = startedAt();

        touch(HOST, Instant.parse("2026-02-01T00:00:00Z"));

        assertThat(startedAt()).isNotEqualTo(first);
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    void hostHash가_없으면_기록하지_않는다() {
        touch(null, null);
        touch("  ", null);

        assertThat(rowCount()).isZero();
    }
}
