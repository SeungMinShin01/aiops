package dev.aiops.server.client;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 디스코드 웹훅 url → 웹훅 id (AI-94). 순수 함수.
 * url은 비밀값이라 여기서 id만 꺼내고, url 자체는 로그·예외에 남기지 않는다 (규칙 40).
 */
public final class WebhookUrls {

    // https://discord.com/api/webhooks/{id}/{token} (discordapp.com, api/v10 경로도 허용)
    private static final Pattern WEBHOOK = Pattern.compile("/webhooks/(\\d+)/[^/?#]+");

    private WebhookUrls() {
    }

    /** id를 꺼낼 수 없으면 empty (형식이 이상한 url) */
    public static Optional<String> webhookId(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        Matcher m = WEBHOOK.matcher(url);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }
}
