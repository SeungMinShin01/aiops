package dev.aiops.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sun.net.httpserver.HttpServer;
import dev.aiops.protocol.ProtocolConstants;
import dev.aiops.server.security.ApiKeyHasher;
import dev.aiops.server.security.ApiKeyResolver;
import dev.aiops.server.support.PostgresContainerSupport;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 마일스톤 2 통합 테스트 (AI-60): 수집 → 그룹화 → 알림이 실제 서버 위에서 끝까지 이어지는지.
 * <p>
 * - 실제 HTTP로 /v1/events 를 보낸다 (키 인증·팀 설정·RLS 경로 그대로)
 * - 디스코드 대신 JDK 내장 HttpServer를 가짜 웹훅으로 띄우고 팀 웹훅 주소를 거기로 둔다 (실제 디스코드 호출 없음, CI에서 돎)
 * - 기다림은 짧은 폴링 (Thread.sleep 금지)
 * - 7단계(LLM)가 아직 없어서 embed 에는 "설명 준비 중"이 실린다. 7단계가 들어오면 FakeLlmClient 로 설명 포함을 확인하도록 바꾼다
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EndToEndFlowTest extends PostgresContainerSupport {

    private static final String RAW_KEY = "aiops_l_e2e-flow-test-key";
    private static final String BATCH = "{\"env\":\"local\","
            + "\"app\":{\"name\":\"sample\",\"commit\":\"abc\",\"hostHash\":\"abc123def456\"},"
            + "\"events\":[{\"time\":1700000000000,\"level\":\"ERROR\","
            + "\"exceptionType\":\"java.lang.NullPointerException\",\"message\":\"boom\","
            + "\"stack\":[{\"className\":\"dev.shop.OrderService\",\"methodName\":\"place\","
            + "\"fileName\":\"OrderService.java\",\"lineNumber\":42}],"
            + "\"causes\":[],\"thread\":\"main\"}],\"droppedCount\":0}";

    @Value("${local.server.port}")
    int port;

    @Autowired ApiKeyResolver resolver;

    private JdbcTemplate owner;
    private HttpServer fakeDiscord;
    private final List<String> webhookBodies = new CopyOnWriteArrayList<>();
    private Long tenantId;

    @BeforeEach
    void setUp() throws IOException {
        // 가짜 디스코드 웹훅: 받은 본문을 모아 두고 204
        fakeDiscord = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        fakeDiscord.createContext("/api/webhooks/", exchange -> {
            webhookBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        fakeDiscord.start();
        String webhook = "http://localhost:" + fakeDiscord.getAddress().getPort() + "/api/webhooks/1/test-token";

        owner = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), OWNER_USER, OWNER_PASSWORD));
        owner.execute("TRUNCATE error_event, error_group, app_snapshot, ingest_stat, heartbeat_last, api_key "
                + "RESTART IDENTITY CASCADE");
        tenantId = owner.queryForObject("INSERT INTO tenant (name, discord_webhook_local) VALUES (?, ?) RETURNING id",
                Long.class, "e2e-" + System.nanoTime(), webhook);
        owner.update("INSERT INTO api_key (tenant_id, key_hash, env) VALUES (?, ?, 'local')",
                tenantId, ApiKeyHasher.sha256Hex(RAW_KEY));
        resolver.evict(ApiKeyHasher.sha256Hex(RAW_KEY));
    }

    @AfterEach
    void tearDown() {
        fakeDiscord.stop(0);
    }

    private int postEvents() throws Exception {
        HttpResponse<String> res = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + ProtocolConstants.PATH_EVENTS))
                        .header("Content-Type", "application/json")
                        .header(ProtocolConstants.HEADER_API_KEY, RAW_KEY)
                        .POST(HttpRequest.BodyPublishers.ofString(BATCH))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        return res.statusCode();
    }

    private long occurrence() {
        List<Long> rows = owner.queryForList("SELECT occurrence_count FROM error_group WHERE tenant_id = ?",
                Long.class, tenantId);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    @Test
    void 새_에러는_웹훅_1번_같은_에러는_추가_알림_없음() throws Exception {
        // 1. 첫 에러 → 202 → 처리 풀에서 그룹 생성 → 웹훅 1번
        assertThat(postEvents()).isEqualTo(202);
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
                .until(() -> webhookBodies.size() == 1);

        assertThat(owner.queryForObject("SELECT count(*) FROM error_group WHERE tenant_id = ?", Long.class, tenantId))
                .isEqualTo(1L);
        String body = webhookBodies.get(0);
        assertThat(body).contains("NullPointerException");
        assertThat(body).contains("설명 준비 중");   // 7단계 전: 설명 없음 (들어오면 설명 포함으로 바꾼다)
        assertThat(body).doesNotContain("test-token"); // 웹훅 주소가 본문에 섞이지 않음

        // 2. 같은 에러 1건 더 → 그룹 횟수 2, 웹훅은 그대로 1번
        assertThat(postEvents()).isEqualTo(202);
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
                .until(() -> occurrence() == 2);
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2))
                .until(() -> webhookBodies.size() == 1);   // 잠시 지켜봐도 추가 알림 없음
    }
}
