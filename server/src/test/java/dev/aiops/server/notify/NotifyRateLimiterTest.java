package dev.aiops.server.notify;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class NotifyRateLimiterTest {

    /** 테스트에서 시간을 앞으로 돌릴 수 있는 시계. */
    static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-11-03T00:00:10Z");

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

    @Test
    void 상한_3이면_3번까지_통과하고_4번째는_막힌다() {
        NotifyRateLimiter limiter = new NotifyRateLimiter(3, new MovableClock());

        assertThat(limiter.tryAcquire(1L)).isTrue();
        assertThat(limiter.tryAcquire(1L)).isTrue();
        assertThat(limiter.tryAcquire(1L)).isTrue();
        assertThat(limiter.tryAcquire(1L)).isFalse();
    }

    @Test
    void 다음_분이_되면_다시_통과한다() {
        MovableClock clock = new MovableClock();
        NotifyRateLimiter limiter = new NotifyRateLimiter(3, clock);
        for (int i = 0; i < 4; i++) {
            limiter.tryAcquire(1L);
        }
        assertThat(limiter.tryAcquire(1L)).isFalse();

        clock.advance(Duration.ofMinutes(1));

        assertThat(limiter.tryAcquire(1L)).isTrue();
    }

    @Test
    void 팀_A가_상한에_닿아도_팀_B는_통과한다() {
        NotifyRateLimiter limiter = new NotifyRateLimiter(3, new MovableClock());
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire(1L);
        }
        assertThat(limiter.tryAcquire(1L)).isFalse();

        assertThat(limiter.tryAcquire(2L)).isTrue();
    }
}
