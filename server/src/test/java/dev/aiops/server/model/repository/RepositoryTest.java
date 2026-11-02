package dev.aiops.server.model.repository;

import dev.aiops.server.model.entity.*;
import dev.aiops.server.support.PostgresContainerSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class RepositoryTest extends PostgresContainerSupport {

    @Autowired
    TenantRepository tenants;
    @Autowired
    ApiKeyRepository apiKeys;
    @Autowired
    ErrorGroupRepository groups;
    @Autowired
    ErrorEventRepository events;
    @Autowired
    AppSnapshotRepository snapshots;
    @Autowired
    EntityManager em;

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
        ErrorEventEntity e = events.saveAndFlush(new ErrorEventEntity(
                t.getId(), "local",
                "{\"exceptionType\":\"java.lang.NullPointerException\"}",
                "sample", null, "abc123"));

        Object type = em.createNativeQuery(
                "select payload->>'exceptionType' from error_event where id = :id")
                .setParameter("id", e.getId())
                .getSingleResult();

        assertThat(type).isEqualTo("java.lang.NullPointerException");
    }

    @Test
    void 같은_팀_같은_지문은_하나의_그룹으로_찾아진다() {
        TenantEntity t = tenants.save(new TenantEntity("테스트 팀"));
        String fp = "b".repeat(64);
        groups.saveAndFlush(new ErrorGroupEntity(t.getId(), fp,
                "java.lang.NullPointerException", "dev.sample.Foo.bar:10", "local"));

        assertThat(groups.findByTenantIdAndFingerprint(t.getId(), fp)).isPresent();
        assertThat(groups.findByTenantIdAndFingerprint(t.getId(), "c".repeat(64))).isEmpty();
    }

    @Test
    void 마지막_hello는_가장_최근_것이다() {
        TenantEntity t = tenants.save(new TenantEntity("테스트 팀"));
        Instant now = Instant.now();
        snapshots.save(new AppSnapshotEntity(t.getId(), "local", "h1", "{\"v\":1}", now.minusSeconds(60)));
        AppSnapshotEntity newer = snapshots.saveAndFlush(
                new AppSnapshotEntity(t.getId(), "local", "h1", "{\"v\":2}", now));

        AppSnapshotEntity latest = snapshots
                .findTopByTenantIdAndEnvOrderByReceivedAtDesc(t.getId(), "local")
                .orElseThrow();

        assertThat(latest.getId()).isEqualTo(newer.getId());
    }
}