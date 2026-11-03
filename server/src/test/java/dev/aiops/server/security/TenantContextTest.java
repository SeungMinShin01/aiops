package dev.aiops.server.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantContextTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void set한_팀을_get과_require로_꺼낸다() {
        TenantContext.set(7L);

        assertThat(TenantContext.get()).contains(7L);
        assertThat(TenantContext.require()).isEqualTo(7L);
    }

    @Test
    void clear하면_비어_있다() {
        TenantContext.set(7L);

        TenantContext.clear();

        assertThat(TenantContext.get()).isEmpty();
    }

    @Test
    void 설정_안_됐으면_get은_empty_require는_TenantNotSetException() {
        assertThat(TenantContext.get()).isEmpty();
        assertThatThrownBy(TenantContext::require).isInstanceOf(TenantNotSetException.class);
    }

    @Test
    void null로_set하면_IllegalArgumentException() {
        assertThatThrownBy(() -> TenantContext.set(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(TenantContext.get()).isEmpty();
    }

    @Test
    void 다른_스레드에서는_보이지_않는다() throws Exception {
        TenantContext.set(7L);
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            Optional<Long> seenByOther = CompletableFuture.supplyAsync(TenantContext::get, other).get(5, TimeUnit.SECONDS);

            assertThat(seenByOther).isEmpty();
            assertThat(TenantContext.get()).contains(7L); // 내 스레드 값은 그대로
        } finally {
            other.shutdownNow();
        }
    }
}
