package dev.aiops.server.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookUrlsTest {

    @Test
    void 디스코드_웹훅_url에서_id를_꺼낸다() {
        assertThat(WebhookUrls.webhookId("https://discord.com/api/webhooks/123456/abc-DEF_tok")).contains("123456");
        assertThat(WebhookUrls.webhookId("https://discordapp.com/api/webhooks/42/tok")).contains("42");
        assertThat(WebhookUrls.webhookId("https://discord.com/api/v10/webhooks/77/tok?wait=true")).contains("77");
    }

    @Test
    void 형식이_이상하면_empty() {
        assertThat(WebhookUrls.webhookId(null)).isEmpty();
        assertThat(WebhookUrls.webhookId("")).isEmpty();
        assertThat(WebhookUrls.webhookId("https://discord.com/api/webhooks/")).isEmpty();
        assertThat(WebhookUrls.webhookId("https://discord.com/api/webhooks/notanumber/tok")).isEmpty();
        assertThat(WebhookUrls.webhookId("https://discord.com/api/webhooks/123")).isEmpty(); // 토큰 없음
    }
}
