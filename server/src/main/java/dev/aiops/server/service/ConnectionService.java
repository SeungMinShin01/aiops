package dev.aiops.server.service;

import dev.aiops.server.dto.ConnectionResponse;
import dev.aiops.server.dto.ConnectionStatus;
import dev.aiops.server.dto.ConnectionStatus.State;
import dev.aiops.server.model.repository.HeartbeatLastRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 연결 확인 (AI-98). heartbeat_last의 env별 마지막 신호로 연결됨/미연결/한 번도 없음을 판정한다.
 * <ul>
 *   <li>데이터 출처는 heartbeat_last뿐 (hello·heartbeat·이벤트 묶음을 받을 때마다 호스트당 1행 UPSERT, D-086).
 *       app_snapshot.received_at은 쓰지 않는다 (hello는 시작 때 1회뿐)</li>
 *   <li>heartbeat_last는 RLS 팀 테이블. MembershipGuard가 TenantContext를 정한 뒤 이 트랜잭션이 열리므로 그 팀 행만 보인다.
 *       쿼리에도 tenant_id 조건을 같이 둔다</li>
 *   <li>기준 시간은 설정값 aiops.console.connected-within (기본 3분 = heartbeat 60초 × 3회 놓침 허용)</li>
 * </ul>
 */
@Service
public class ConnectionService {

    static final List<String> ENVS = List.of("local", "prod");

    private final HeartbeatLastRepository heartbeats;
    private final Duration within;
    private final Clock clock;

    @Autowired
    public ConnectionService(HeartbeatLastRepository heartbeats,
            @Value("${aiops.console.connected-within:3m}") Duration within) {
        this(heartbeats, within, Clock.systemUTC());
    }

    ConnectionService(HeartbeatLastRepository heartbeats, Duration within, Clock clock) {
        this.heartbeats = heartbeats;
        this.within = within;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ConnectionResponse status(Long tenantId) {
        Instant now = clock.instant();
        List<Object[]> rows = heartbeats.lastSeenByEnv(tenantId, now.minus(within));
        Map<String, ConnectionStatus> byEnv = summarize(rows, now, within);
        return new ConnectionResponse(byEnv.get("local"), byEnv.get("prod"));
    }

    /** 쿼리 결과 [env, max(last_seen_at), 최근 호스트 수] → env별 상태. 행이 없는 env는 NEVER */
    static Map<String, ConnectionStatus> summarize(List<Object[]> rows, Instant now, Duration within) {
        Map<String, ConnectionStatus> result = new HashMap<>();
        for (String env : ENVS) {
            result.put(env, ConnectionStatus.never());
        }
        for (Object[] row : rows) {
            String env = (String) row[0];
            if (!result.containsKey(env)) {
                continue;   // local·prod 외의 값은 무시 (수집 단계에서 이미 막지만 혹시 몰라)
            }
            Instant lastSeenAt = toInstant(row[1]);
            long hostCount = row[2] == null ? 0 : ((Number) row[2]).longValue();
            result.put(env, new ConnectionStatus(judge(lastSeenAt, now, within), lastSeenAt, hostCount));
        }
        return result;
    }

    /** 판정 (순수 함수). now - lastSeenAt <= within 이면 CONNECTED. 신호가 없으면 NEVER */
    public static State judge(Instant lastSeenAt, Instant now, Duration within) {
        if (lastSeenAt == null) {
            return State.NEVER;
        }
        return Duration.between(lastSeenAt, now).compareTo(within) <= 0 ? State.CONNECTED : State.NOT_CONNECTED;
    }

    // 네이티브 쿼리의 timestamptz는 드라이버·Hibernate 버전에 따라 타입이 달라서 셋 다 받는다
    private static Instant toInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        if (value instanceof Timestamp ts) {
            return ts.toInstant();
        }
        throw new IllegalStateException("알 수 없는 시각 타입: " + value.getClass().getName());
    }
}
