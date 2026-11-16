package dev.aiops.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aiops.server.dto.ConnectionStatus;
import dev.aiops.server.dto.ConnectionStatus.State;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 연결 판정 (AI-98). DB 없이 순수 함수만 */
class ConnectionServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-16T03:00:00Z");
    private static final Duration WITHIN = Duration.ofMinutes(3);

    @Test
    void 마지막_신호가_2분_59초_전이면_CONNECTED() {
        assertThat(ConnectionService.judge(NOW.minusSeconds(179), NOW, WITHIN)).isEqualTo(State.CONNECTED);
    }

    @Test
    void 정확히_3분이면_CONNECTED_3분_1초면_NOT_CONNECTED() {
        assertThat(ConnectionService.judge(NOW.minusSeconds(180), NOW, WITHIN)).isEqualTo(State.CONNECTED);
        assertThat(ConnectionService.judge(NOW.minusSeconds(181), NOW, WITHIN)).isEqualTo(State.NOT_CONNECTED);
    }

    @Test
    void 신호가_없으면_NEVER() {
        assertThat(ConnectionService.judge(null, NOW, WITHIN)).isEqualTo(State.NEVER);
    }

    @Test
    void 기준_시간은_설정값을_따른다() {
        assertThat(ConnectionService.judge(NOW.minusSeconds(240), NOW, Duration.ofMinutes(5))).isEqualTo(State.CONNECTED);
    }

    @Test
    void local만_있고_prod는_행이_없으면_prod는_NEVER() {
        List<Object[]> rows = List.<Object[]>of(new Object[] {"local", NOW.minusSeconds(30), 1L});

        Map<String, ConnectionStatus> result = ConnectionService.summarize(rows, NOW, WITHIN);

        assertThat(result.get("local")).isEqualTo(new ConnectionStatus(State.CONNECTED, NOW.minusSeconds(30), 1));
        assertThat(result.get("prod")).isEqualTo(ConnectionStatus.never());
    }

    @Test
    void 호스트_2개_중_1개만_최근이면_hostCount_1() {
        // 쿼리가 env별로 max(last_seen_at)와 최근 3분 안 호스트 수를 돌려준 상황
        List<Object[]> rows = List.<Object[]>of(new Object[] {"local", NOW.minusSeconds(10), 1L});

        assertThat(ConnectionService.summarize(rows, NOW, WITHIN).get("local").hostCount()).isEqualTo(1);
    }

    @Test
    void 오래된_신호만_있으면_NOT_CONNECTED이고_hostCount_0() {
        List<Object[]> rows = List.<Object[]>of(new Object[] {"prod", NOW.minusSeconds(600), 0L});

        ConnectionStatus prod = ConnectionService.summarize(rows, NOW, WITHIN).get("prod");

        assertThat(prod.state()).isEqualTo(State.NOT_CONNECTED);
        assertThat(prod.lastSeenAt()).isEqualTo(NOW.minusSeconds(600));
        assertThat(prod.hostCount()).isZero();
    }

    @Test
    void 시각_타입이_OffsetDateTime이어도_읽는다() {
        OffsetDateTime odt = OffsetDateTime.ofInstant(NOW.minusSeconds(5), ZoneOffset.UTC);
        List<Object[]> rows = List.<Object[]>of(new Object[] {"local", odt, 2});

        assertThat(ConnectionService.summarize(rows, NOW, WITHIN).get("local").lastSeenAt())
                .isEqualTo(NOW.minusSeconds(5));
    }
}
