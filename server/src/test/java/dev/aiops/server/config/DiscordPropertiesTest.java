package dev.aiops.server.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DiscordPropertiesTest {

    @Test
    void toString에_봇_토큰과_client_secret이_찍히지_않는다() {
        DiscordProperties props = new DiscordProperties(
                "123456789", "fake-bot-token-value", "987654321", "fake-client-secret-value",
                "http://localhost:8080/api/discord/callback");

        String printed = props.toString();

        assertThat(printed).doesNotContain("fake-bot-token-value").doesNotContain("fake-client-secret-value");
        assertThat(printed).contains("123456789").contains("botToken=***").contains("clientSecret=***");
    }

    @Test
    void 봇_토큰이나_서버_id가_비어_있으면_설정_안_됨() {
        assertThat(new DiscordProperties("", "token", null, null, null).isBotConfigured()).isFalse();
        assertThat(new DiscordProperties("123", "", null, null, null).isBotConfigured()).isFalse();
        assertThat(new DiscordProperties(null, null, null, null, null).isBotConfigured()).isFalse();
        assertThat(new DiscordProperties("123", "token", null, null, null).isBotConfigured()).isTrue();
    }

    @Test
    void 비어_있는_비밀값은_없음으로_표시한다() {
        String printed = new DiscordProperties(null, null, null, null, null).toString();

        assertThat(printed).contains("botToken=(없음)").contains("clientSecret=(없음)");
    }
}
