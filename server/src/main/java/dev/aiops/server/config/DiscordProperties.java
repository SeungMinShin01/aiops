package dev.aiops.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 디스코드 연동 설정 (aiops.discord.*). 값은 전부 환경변수에서 온다 (규칙 10, 40).
 * <p>
 * 서버 id는 설정값으로 고정한다. 코드가 "봇이 들어간 서버 목록"을 보고 고르지 않는다.
 * toString에는 봇 토큰·client secret이 찍히지 않는다 (로그·예외 메시지로 새지 않게).
 *
 * @param guildId      우리 디스코드 서버 id (AIOPS_DISCORD_GUILD_ID)
 * @param botToken     봇 토큰 (AIOPS_DISCORD_BOT_TOKEN) — 비밀값
 * @param clientId     OAuth2 앱 client id (AIOPS_DISCORD_CLIENT_ID)
 * @param clientSecret OAuth2 앱 client secret (AIOPS_DISCORD_CLIENT_SECRET) — 비밀값
 * @param redirectUri  팀원 연결 콜백 주소 (AIOPS_DISCORD_REDIRECT_URI)
 */
@ConfigurationProperties("aiops.discord")
public record DiscordProperties(
        String guildId,
        String botToken,
        String clientId,
        String clientSecret,
        String redirectUri) {

    /** 봇 토큰과 서버 id가 둘 다 있어야 디스코드 API를 부를 수 있다. 비어 있으면 연동 기능은 건너뛴다. */
    public boolean isBotConfigured() {
        return hasText(guildId) && hasText(botToken);
    }

    @Override
    public String toString() {
        return "DiscordProperties[guildId=" + guildId
                + ", botToken=" + mask(botToken)
                + ", clientId=" + clientId
                + ", clientSecret=" + mask(clientSecret)
                + ", redirectUri=" + redirectUri + "]";
    }

    private static String mask(String secret) {
        return hasText(secret) ? "***" : "(없음)";
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
