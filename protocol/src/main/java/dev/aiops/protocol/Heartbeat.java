package dev.aiops.protocol;

/**
 * POST /v1/heartbeat 본문. 보낼 에러가 없어도 HEARTBEAT_INTERVAL_SECONDS마다 전송 (D-086).
 * 서버는 받으면 heartbeat_last.last_seen_at을 갱신하고, 3분 안에 신호가 있으면 "연결됨"으로 본다.
 * sentAt: epoch millis (UTC). ErrorEvent.time과 같은 형식
 */
public record Heartbeat(String env, AppInfo app, long sentAt) {
}