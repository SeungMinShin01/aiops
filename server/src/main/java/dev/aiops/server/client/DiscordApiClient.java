package dev.aiops.server.client;

import dev.aiops.server.config.DiscordProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 디스코드 REST API(v10)를 부르는 얼굴 하나 (AI-91). 요청 한 번 = 메서드 하나.
 * <p>
 * "무엇을 언제 만드는가", "이미 있으면 건너뛴다" 같은 정책은 모른다 — 그건 DiscordProvisioner 몫이다.
 * <ul>
 *   <li>429 → Retry-After 만큼 기다렸다가 1회만 재시도. 또 실패하면 DiscordApiException</li>
 *   <li>4xx/5xx/타임아웃 → DiscordApiException(status, 호출 이름). 요청·응답 본문은 담지 않는다</li>
 *   <li>사용자 access_token은 addMember 파라미터로만 받고 보관하지 않는다</li>
 * </ul>
 */
@Component
public class DiscordApiClient {

    /** 디스코드 권한 비트 VIEW_CHANNEL. 큰 정수라 문자열로 보낸다. */
    static final String VIEW_CHANNEL = "1024";
    static final String WEBHOOK_URL_PREFIX = "https://discord.com/api/webhooks/";
    /** Retry-After가 비정상적으로 길어도 요청 스레드를 오래 붙잡지 않게 */
    static final Duration MAX_RETRY_WAIT = Duration.ofSeconds(30);

    /** addMember 결과. 이미 멤버면 디스코드가 roles를 적용하지 않으므로 호출한 쪽이 addRole을 따로 부른다. */
    public enum AddMemberResult { ADDED, ALREADY_MEMBER }

    /** 테스트에서 실제로 잠들지 않게 바꿔 끼우는 부분 */
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final RestClient rest;
    private final DiscordProperties properties;
    private final Sleeper sleeper;
    private volatile String botUserId;

    @Autowired
    public DiscordApiClient(@Qualifier("discordRestClient") RestClient discordRestClient, DiscordProperties properties) {
        this(discordRestClient, properties, d -> Thread.sleep(d.toMillis()));
    }

    DiscordApiClient(RestClient rest, DiscordProperties properties, Sleeper sleeper) {
        this.rest = rest;
        this.properties = properties;
        this.sleeper = sleeper;
    }

    // ---- 역할·카테고리·채널·웹훅 ----

    /** POST /guilds/{guild}/roles → 역할 id */
    public String createRole(String name) {
        Map<String, Object> body = Map.of("name", name, "permissions", "0", "mentionable", false);
        return idOf(call("createRole", () -> rest.post()
                .uri("/guilds/{guild}/roles", guildId())
                .body(body)
                .retrieve()
                .body(Map.class)));
    }

    /** POST /guilds/{guild}/channels type=4. @everyone은 못 보고, 팀 역할과 봇만 본다 → 카테고리 id */
    public String createCategory(String name, String roleId) {
        String guild = guildId();
        List<Map<String, Object>> overwrites = List.of(
                Map.of("id", guild, "type", 0, "deny", VIEW_CHANNEL),       // @everyone (역할 id = 서버 id)
                Map.of("id", roleId, "type", 0, "allow", VIEW_CHANNEL),     // 팀 역할
                Map.of("id", botUserId(), "type", 1, "allow", VIEW_CHANNEL)); // 봇 자신 (type 1 = 멤버)
        Map<String, Object> body = Map.of("name", name, "type", 4, "permission_overwrites", overwrites);
        return idOf(call("createCategory", () -> rest.post()
                .uri("/guilds/{guild}/channels", guild)
                .body(body)
                .retrieve()
                .body(Map.class)));
    }

    /** POST /guilds/{guild}/channels type=0, parent=카테고리 (권한은 카테고리에서 상속) → 채널 id */
    public String createTextChannel(String name, String categoryId) {
        Map<String, Object> body = Map.of("name", name, "type", 0, "parent_id", categoryId);
        return idOf(call("createTextChannel", () -> rest.post()
                .uri("/guilds/{guild}/channels", guildId())
                .body(body)
                .retrieve()
                .body(Map.class)));
    }

    /** POST /channels/{channelId}/webhooks → id와 url (응답에 url이 없으면 id+token으로 조립) */
    public WebhookInfo createWebhook(String channelId, String name) {
        Map<?, ?> res = call("createWebhook", () -> rest.post()
                .uri("/channels/{channelId}/webhooks", channelId)
                .body(Map.of("name", name))
                .retrieve()
                .body(Map.class));
        if (res == null || res.get("id") == null) {
            throw new DiscordApiException(DiscordApiException.NO_RESPONSE, "createWebhook");
        }
        String id = String.valueOf(res.get("id"));
        Object url = res.get("url");
        String webhookUrl = (url != null) ? url.toString() : WEBHOOK_URL_PREFIX + id + "/" + res.get("token");
        return new WebhookInfo(id, webhookUrl);
    }

    /** DELETE /webhooks/{id}. 이미 없으면(404) 성공으로 본다 */
    public void deleteWebhook(String webhookId) {
        callIgnoring404("deleteWebhook", () -> rest.delete()
                .uri("/webhooks/{id}", webhookId)
                .retrieve()
                .toBodilessEntity());
    }

    /** DELETE /channels/{id} (테스트 정리용. 운영 코드는 부르지 않는다) */
    public void deleteChannel(String channelId) {
        call("deleteChannel", () -> rest.delete()
                .uri("/channels/{id}", channelId)
                .retrieve()
                .toBodilessEntity());
    }

    /** DELETE /guilds/{guild}/roles/{id} (테스트 정리용. 운영 코드는 부르지 않는다) */
    public void deleteRole(String roleId) {
        call("deleteRole", () -> rest.delete()
                .uri("/guilds/{guild}/roles/{id}", guildId(), roleId)
                .retrieve()
                .toBodilessEntity());
    }

    // ---- 멤버 ----

    /**
     * PUT /guilds/{guild}/members/{userId} — 사용자를 서버에 넣고 역할을 붙인다 (봇 토큰으로 호출).
     * 201 = 새로 추가, 204 = 이미 멤버 (이 경우 roles는 적용되지 않으니 addRole을 따로 부른다).
     * userAccessToken은 이 호출에만 쓰고 어디에도 보관하지 않는다.
     */
    public AddMemberResult addMember(String discordUserId, String userAccessToken, List<String> roleIds) {
        Map<String, Object> body = Map.of("access_token", userAccessToken, "roles", roleIds);
        HttpStatusCode status = call("addMember", () -> rest.put()
                .uri("/guilds/{guild}/members/{userId}", guildId(), discordUserId)
                .body(body)
                .retrieve()
                .toBodilessEntity()
                .getStatusCode());
        return status.value() == 204 ? AddMemberResult.ALREADY_MEMBER : AddMemberResult.ADDED;
    }

    /** PUT /guilds/{guild}/members/{userId}/roles/{roleId} */
    public void addRole(String discordUserId, String roleId) {
        call("addRole", () -> rest.put()
                .uri("/guilds/{guild}/members/{userId}/roles/{roleId}", guildId(), discordUserId, roleId)
                .retrieve()
                .toBodilessEntity());
    }

    /** DELETE /guilds/{guild}/members/{userId}/roles/{roleId}. 이미 없으면(404) 성공으로 본다 */
    public void removeRole(String discordUserId, String roleId) {
        callIgnoring404("removeRole", () -> rest.delete()
                .uri("/guilds/{guild}/members/{userId}/roles/{roleId}", guildId(), discordUserId, roleId)
                .retrieve()
                .toBodilessEntity());
    }

    /** GET /users/@me — 봇 자신의 사용자 id. 1회 호출 후 캐시 (카테고리 overwrite에 필요) */
    public String botUserId() {
        String cached = botUserId;
        if (cached == null) {
            cached = idOf(call("botUserId", () -> rest.get()
                    .uri("/users/@me")
                    .retrieve()
                    .body(Map.class)));
            botUserId = cached;
        }
        return cached;
    }

    // ---- 공통 ----

    private String guildId() {
        return properties.guildId();
    }

    private static String idOf(Map<?, ?> response) {
        if (response == null || response.get("id") == null) {
            throw new DiscordApiException(DiscordApiException.NO_RESPONSE, "응답에 id 없음");
        }
        return String.valueOf(response.get("id"));
    }

    private void callIgnoring404(String operation, Supplier<?> request) {
        try {
            call(operation, request);
        } catch (DiscordApiException e) {
            if (e.getStatus() != 404) {
                throw e;
            }
        }
    }

    /** 1회 호출, 429면 Retry-After만큼 기다렸다가 1회만 다시. 실패는 전부 DiscordApiException으로 바꾼다. */
    private <T> T call(String operation, Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() != 429) {
                throw new DiscordApiException(e.getStatusCode().value(), operation);
            }
            waitBeforeRetry(retryAfter(e), operation);
        } catch (ResourceAccessException e) {
            throw new DiscordApiException(DiscordApiException.NO_RESPONSE, operation);
        }

        try {
            return request.get();
        } catch (RestClientResponseException e) {
            throw new DiscordApiException(e.getStatusCode().value(), operation);
        } catch (ResourceAccessException e) {
            throw new DiscordApiException(DiscordApiException.NO_RESPONSE, operation);
        }
    }

    /** Retry-After 헤더(초, 소수 가능). 없거나 이상하면 1초 */
    static Duration retryAfter(RestClientResponseException e) {
        String header = (e.getResponseHeaders() == null) ? null : e.getResponseHeaders().getFirst("Retry-After");
        try {
            long millis = (long) (Double.parseDouble(header) * 1000);
            Duration wait = Duration.ofMillis(Math.max(millis, 0));
            return wait.compareTo(MAX_RETRY_WAIT) > 0 ? MAX_RETRY_WAIT : wait;
        } catch (NullPointerException | NumberFormatException ex) {
            return Duration.ofSeconds(1);
        }
    }

    private void waitBeforeRetry(Duration wait, String operation) {
        try {
            sleeper.sleep(wait);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new DiscordApiException(429, operation);
        }
    }
}
