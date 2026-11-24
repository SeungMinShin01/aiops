package dev.aiops.server.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OAuthStateTest {

    static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-11-04T00:00:00Z");

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

    MovableClock clock = new MovableClock();
    OAuthState state = new OAuthState("test-state-secret", clock);

    @Test
    void 만든_state를_검증하면_사용자와_팀이_나온다() {
        String s = state.create(7L, 42L);

        assertThat(state.verify(s)).isEqualTo(new OAuthState.Payload(7L, 42L));
    }

    @Test
    void 팀_번호를_바꿔치기하면_400() {
        String s = state.create(7L, 42L);
        String payload = new String(Base64.getUrlDecoder().decode(s.substring(0, s.indexOf('.'))), StandardCharsets.UTF_8);
        String forgedPayload = payload.replace(":42:", ":99:");
        String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(forgedPayload.getBytes(StandardCharsets.UTF_8))
                + s.substring(s.indexOf('.'));

        assertThatThrownBy(() -> state.verify(forged))
                .isInstanceOf(InvalidOAuthStateException.class).hasMessageContaining("signature");
    }

    @Test
    void 다른_비밀키로_만든_state는_400() {
        String s = new OAuthState("other-secret", clock).create(7L, 42L);

        assertThatThrownBy(() -> state.verify(s)).isInstanceOf(InvalidOAuthStateException.class);
    }

    @Test
    void 만료는_10분_지나면_400() {
        String s = state.create(7L, 42L);

        clock.advance(Duration.ofMinutes(9));
        assertThat(state.verify(s).teamId()).isEqualTo(42L);

        clock.advance(Duration.ofMinutes(1));
        assertThatThrownBy(() -> state.verify(s))
                .isInstanceOf(InvalidOAuthStateException.class).hasMessageContaining("expired");
    }

    @Test
    void 형식이_이상하면_400() {
        for (String bad : new String[] {null, "", "no-dot", ".", "abc.", ".abc", "@@@.###"}) {
            assertThatThrownBy(() -> state.verify(bad)).as(String.valueOf(bad))
                    .isInstanceOf(InvalidOAuthStateException.class);
        }
    }

    @Test
    void 비밀키가_없으면_만들지도_검증하지도_않는다() {
        OAuthState noSecret = new OAuthState("", clock);

        assertThatThrownBy(() -> noSecret.create(7L, 42L)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> noSecret.verify("a.b")).isInstanceOf(IllegalStateException.class);
    }
}
