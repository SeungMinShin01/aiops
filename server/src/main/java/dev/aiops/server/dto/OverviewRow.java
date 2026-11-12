package dev.aiops.server.dto;

import dev.aiops.server.model.entity.DiscordStatus;
import dev.aiops.server.model.entity.TenantStatus;
import java.time.Instant;

// AI-86 : 관리자 전체 현황 한 줄 (팀 하나). 상태 값·시각·개수만 담는다 (웹훅 주소·키 해시·채널 id·host_hash·에러 내용 금지, 규칙 10)
// lastSeenLocal / lastSeenProd = env 별 마지막 신호 시각 (heartbeat_last.last_seen_at 의 최댓값). 한 번도 없으면 null
// "연결됨(3분 이내)" 판정은 콘솔이 한다. 서버는 시각만 준다
// errors24h 는 최근 24시간 에러 수. 그 팀을 집계하다 실패하면 null (다른 팀 줄은 정상)
public record OverviewRow(Long teamId, String name, String cohort, TenantStatus status, String ownerLogin,
                          Instant lastSeenLocal, Instant lastSeenProd, Long errors24h, DiscordStatus discordStatus) {
}