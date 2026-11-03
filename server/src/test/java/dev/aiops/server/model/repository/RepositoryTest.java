package dev.aiops.server.model.repository;

import dev.aiops.server.model.entity.*;
import dev.aiops.server.security.TenantContext;
import dev.aiops.server.support.PostgresContainerSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 리포지토리 기본 동작. V2(RLS) 이후에도 돌도록:
 * - 클래스에 @Transactional을 두지 않는다 (메서드 시작 전에 트랜잭션이 열리면 TenantContext가 set_config에 반영되지 않는다)
 * - 팀 테이블 작업은 TenantContext.set() 뒤 TransactionTemplate 안에서
 * - 롤백이 없으므로 시작할 때 owner(RLS 무시)로 비운다
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RepositoryTest extends PostgresContainerSupport {

    @Autowired TenantRepository tenants;
    @Autowired ApiKeyRepository apiKeys;
    @Autowired ErrorGroupRepository groups;
    @Autowired ErrorEventRepository events;
    @Autowired AppSnapshotRepository snapshots;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), OWNER_USER, OWNER_PASSWORD))
                .execute("TRUNCATE error_event, error_group, app_snapshot, ingest_stat, heartbeat_last, api_key, tenant RESTART IDENTITY CASCADE");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    // api_key·tenant는 RLS 대상이 아니라 팀 설정 없이 동작한다
    @Test
    void 키_해시로_찾고_폐기하면_안_찾아진다() {
        TenantEntity t = tenants.save(new TenantEntity("테스트 팀"));
        String hash = "a".repeat(64);
        ApiKeyEntity key = apiKeys.saveAndFlush(new ApiKeyEntity(t.getId(), hash, "local"));

        assertThat(apiKeys.findByKeyHashAndRevokedAtIsNull(hash)).isPresent();

        key.revoke();
        apiKeys.saveAndFlush(key);

        assertThat(apiKeys.findByKeyHashAndRevokedAtIsNull(hash)).isEmpty();
    }

    @Test
    void payload가_jsonb로_저장되어_DB에서_필드를_꺼낼_수_있다() {
        TenantEntity t = tenants.save(new TenantEntity("테스트 팀"));
        TenantContext.set(t.getId());

        Object type = tx.execute(s -> {
            ErrorEventEntity e = events.saveAndFlush(new ErrorEventEntity(
                    t.getId(), "local",
                    "{\"exceptionType\":\"java.lang.NullPointerException\"}",
                    "sample", null, "abc123"));
            return em.createNativeQuery("select payload->>'exceptionType' from error_event where id = :id")
                    .setParameter("id", e.getId())
                    .getSingleResult();
        });

        assertThat(type).isEqualTo("java.lang.NullPointerException");
    }

    @Test
    void 같은_팀_같은_지문은_하나의_그룹으로_찾아진다() {
        TenantEntity t = tenants.save(new TenantEntity("테스트 팀"));
        TenantContext.set(t.getId());
        String fp = "b".repeat(64);

        tx.executeWithoutResult(s -> groups.saveAndFlush(new ErrorGroupEntity(t.getId(), fp,
                "java.lang.NullPointerException", "dev.sample.Foo.bar:10", "local")));

        // tx.execute(...)를 assertThat 안에 바로 넣으면 제네릭 T가 assertThat 오버로드 쪽으로 추론돼 ambiguous → 변수에 담는다
        Optional<ErrorGroupEntity> found = tx.execute(s -> groups.findByTenantIdAndFingerprint(t.getId(), fp));
        Optional<ErrorGroupEntity> notFound = tx.execute(s -> groups.findByTenantIdAndFingerprint(t.getId(), "c".repeat(64)));

        assertThat(found).isPresent();
        assertThat(notFound).isEmpty();
    }

    @Test
    void 마지막_hello는_가장_최근_것이다() {
        TenantEntity t = tenants.save(new TenantEntity("테스트 팀"));
        TenantContext.set(t.getId());
        Instant now = Instant.now();

        Long newerId = tx.execute(s -> {
            snapshots.save(new AppSnapshotEntity(t.getId(), "local", "h1", "{\"v\":1}", now.minusSeconds(60)));
            return snapshots.saveAndFlush(new AppSnapshotEntity(t.getId(), "local", "h1", "{\"v\":2}", now)).getId();
        });

        AppSnapshotEntity latest = tx.execute(s -> snapshots
                .findTopByTenantIdAndEnvOrderByReceivedAtDesc(t.getId(), "local")
                .orElseThrow());

        assertThat(latest.getId()).isEqualTo(newerId);
    }
}