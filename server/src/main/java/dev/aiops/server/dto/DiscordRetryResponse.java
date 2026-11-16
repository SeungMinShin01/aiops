package dev.aiops.server.dto;

import dev.aiops.server.model.entity.DiscordStatus;

/**
 * 관리자 디스코드 채널 재시도 결과 (AI-92).
 * result: READY(이번에 다 만듦) / ALREADY_READY(이미 준비됨) / FAILED(어느 단계에서 실패, discordError에 "단계:HTTP상태")
 * 웹훅 주소·채널 id는 넣지 않는다 (규칙 40).
 */
public record DiscordRetryResponse(long teamId, String result, DiscordStatus discordStatus, String discordError) {
}
