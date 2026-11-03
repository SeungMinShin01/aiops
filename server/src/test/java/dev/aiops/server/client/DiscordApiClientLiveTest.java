package dev.aiops.server.client;

import dev.aiops.server.config.DiscordClientConfig;
import dev.aiops.server.config.DiscordProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 진짜 디스코드 <b>테스트 서버</b>에 역할 → 카테고리 → 채널 → 웹훅을 만들고 역순으로 지운다 (AI-91 완료 기준).
 * 환경변수 AIOPS_DISCORD_BOT_TOKEN·AIOPS_DISCORD_GUILD_ID가 있을 때만 돈다 — CI에서는 건너뛴다.
 * 운영 서버 id로 돌리지 않는다.
 */
@EnabledIfEnvironmentVariable(named = "AIOPS_DISCORD_BOT_TOKEN", matches = ".+")
@EnabledIfEnvironmentVariable(named = "AIOPS_DISCORD_GUILD_ID", matches = ".+")
class DiscordApiClientLiveTest {

    @Test
    void 테스트_서버에서_역할_카테고리_채널_웹훅을_만들고_지운다() {
        DiscordProperties props = new DiscordProperties(
                System.getenv("AIOPS_DISCORD_GUILD_ID"), System.getenv("AIOPS_DISCORD_BOT_TOKEN"), null, null, null);
        RestClient rest = DiscordClientConfig.create(props);
        DiscordApiClient client = new DiscordApiClient(rest, props);

        String suffix = String.valueOf(System.currentTimeMillis() % 100000);
        String roleId = null;
        String categoryId = null;
        String channelId = null;
        WebhookInfo hook = null;
        try {
            roleId = client.createRole("team-livetest-" + suffix);
            categoryId = client.createCategory("livetest-" + suffix, roleId);
            channelId = client.createTextChannel("livetest-" + suffix + "-local", categoryId);
            hook = client.createWebhook(channelId, "aiops-livetest");

            assertThat(hook.id()).isNotBlank();
            assertThat(hook.url()).startsWith("https://discord.com/api/webhooks/"); // 값은 출력하지 않는다

            // 채널이 카테고리 권한을 이어받아 @everyone에게 안 보이는지 (GET /channels/{id})
            Map<?, ?> channel = rest.get().uri("/channels/{id}", channelId).retrieve().body(Map.class);
            List<?> overwrites = (List<?>) channel.get("permission_overwrites");
            String guildId = props.guildId();
            String everyoneRole = guildId;
            String teamRole = roleId;
            assertThat(overwrites).anySatisfy(o -> {
                Map<?, ?> m = (Map<?, ?>) o;
                assertThat(String.valueOf(m.get("id"))).isEqualTo(everyoneRole);
                assertThat((Long.parseLong(String.valueOf(m.get("deny"))) & 1024L)).isEqualTo(1024L);
            });
            assertThat(overwrites).anySatisfy(o -> {
                Map<?, ?> m = (Map<?, ?>) o;
                assertThat(String.valueOf(m.get("id"))).isEqualTo(teamRole);
                assertThat((Long.parseLong(String.valueOf(m.get("allow"))) & 1024L)).isEqualTo(1024L);
            });
        } finally {
            // 역순 정리 (테스트 서버를 어지럽히지 않게)
            if (hook != null) {
                client.deleteWebhook(hook.id());
            }
            if (channelId != null) {
                client.deleteChannel(channelId);
            }
            if (categoryId != null) {
                client.deleteChannel(categoryId);
            }
            if (roleId != null) {
                client.deleteRole(roleId);
            }
        }
    }
}
