package dev.aiops.server.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyCacheTest {

    /** 테스트에서 시간을 앞으로 돌릴 수 있는 시계 */
    static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-11-03T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    static final ResolvedKey TEAM_1 = new ResolvedKey(1L, "local", 10L);

    @Test
    void TTL_안에서는_값을_돌려주고_지나면_null() {
        MovableClock clock = new MovableClock();
        ApiKeyCache cache = new ApiKeyCache(Duration.ofSeconds(60), clock, 100);
        cache.put("hash-1", TEAM_1);

        clock.advance(Duration.ofSeconds(59));
        assertThat(cache.get("hash-1")).isEqualTo(TEAM_1);

        clock.advance(Duration.ofSeconds(1)); // 정확히 60초 = 만료
        assertThat(cache.get("hash-1")).isNull();
        assertThat(cache.size()).isZero(); // 만료 항목은 꺼낼 때 지워진다
    }

    @Test
    void evict하면_즉시_없어진다() {
        ApiKeyCache cache = new ApiKeyCache(Duration.ofSeconds(60), new MovableClock(), 100);
        cache.put("hash-1", TEAM_1);

        cache.evict("hash-1");

        assertThat(cache.get("hash-1")).isNull();
    }

    @Test
    void 상한을_넘으면_만료된_항목부터_정리한다() {
        MovableClock clock = new MovableClock();
        ApiKeyCache cache = new ApiKeyCache(Duration.ofSeconds(60), clock, 3);
        cache.put("old-1", TEAM_1);
        cache.put("old-2", TEAM_1);
        clock.advance(Duration.ofSeconds(61)); // old-1·2 만료
        cache.put("new-1", TEAM_1);

        cache.put("new-2", TEAM_1); // 3개 꽉 참 → 만료된 old-1·2 정리 후 들어감

        assertThat(cache.size()).isEqualTo(2);
        assertThat(cache.get("new-1")).isEqualTo(TEAM_1);
        assertThat(cache.get("new-2")).isEqualTo(TEAM_1);
    }

    @Test
    void 만료된_게_없어도_상한은_지킨다_곧_만료될_것부터() {
        MovableClock clock = new MovableClock();
        ApiKeyCache cache = new ApiKeyCache(Duration.ofSeconds(60), clock, 2);
        cache.put("first", TEAM_1);
        clock.advance(Duration.ofSeconds(1));
        cache.put("second", TEAM_1);
        clock.advance(Duration.ofSeconds(1));

        cache.put("third", TEAM_1);

        assertThat(cache.size()).isEqualTo(2);
        assertThat(cache.get("first")).isNull(); // 가장 먼저 만료될 항목이 나감
        assertThat(cache.get("third")).isEqualTo(TEAM_1);
    }
}
