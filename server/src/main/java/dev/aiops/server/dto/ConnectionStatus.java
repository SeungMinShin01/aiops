package dev.aiops.server.dto;

import java.time.Instant;

/**
 * env 하나의 연결 상태 (AI-98). 시각·상태·개수만 — host_hash 값, app_snapshot 내용은 넣지 않는다.
 *
 * @param state      CONNECTED(마지막 신호가 기준 시간 안) / NOT_CONNECTED(넘음) / NEVER(신호가 한 번도 없음)
 * @param lastSeenAt 마지막 신호 시각. NEVER면 null
 * @param hostCount  기준 시간 안에 신호를 보낸 호스트 수 (팀원 몇 명이 앱을 켜 두었는지)
 */
public record ConnectionStatus(State state, Instant lastSeenAt, long hostCount) {

    public enum State { CONNECTED, NOT_CONNECTED, NEVER }

    public static ConnectionStatus never() {
        return new ConnectionStatus(State.NEVER, null, 0);
    }
}
