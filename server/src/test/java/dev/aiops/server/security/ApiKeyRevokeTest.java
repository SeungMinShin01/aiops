package dev.aiops.server.security;

import dev.aiops.protocol.ProtocolConstants;
import dev.aiops.server.controller.IngestBodySizeFilter;
import dev.aiops.server.model.entity.ApiKeyEntity;
import dev.aiops.server.model.entity.TenantEntity;
import dev.aiops.server.model.repository.ApiKeyRepository;
import dev.aiops.server.model.repository.TenantRepository;
import dev.aiops.server.service.ApiKeyService;
import dev.aiops.server.support.PostgresContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 키 캐시가 따뜻한 상태에서 폐기 → 즉시 401 (AI-74). 진짜 DB + TenantFilter + 수집 API.
 */
@SpringBootTest
class ApiKeyRevokeTest extends PostgresContainerSupport {

    private static final String RAW_KEY = "revoke-test-key-0001";
    // 이벤트 없는 묶음 (버린 개수만 알리는 경우와 같음) — 요청을 많이 보내도 가볍게
    private static final String EMPTY_BATCH =
            "{\"env\":\"local\",\"app\":{\"name\":\"t\",\"hostHash\":\"h1\"},\"events\":[],\"droppedCount\":0}";

    @Autowired WebApplicationContext context;
    @Autowired TenantFilter tenantFilter;
    @Autowired IngestBodySizeFilter bodySizeFilter;
    @Autowired TenantRepository tenants;
    @Autowired ApiKeyRepository apiKeys;
    @Autowired ApiKeyService apiKeyService;
    @Autowired ApiKeyResolver resolver;

    MockMvc mockMvc;
    Long keyId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(tenantFilter, bodySizeFilter)
                .build();
        new JdbcTemplate(new DriverManagerDataSource(jdbcUrl(), OWNER_USER, OWNER_PASSWORD))
                .execute("TRUNCATE error_event, error_group, app_snapshot, ingest_stat, heartbeat_last, api_key, tenant RESTART IDENTITY CASCADE");
        resolver.evict(ApiKeyHasher.sha256Hex(RAW_KEY)); // 앞 테스트가 남긴 캐시 정리
        Long tenantId = tenants.save(new TenantEntity("폐기 테스트 팀")).getId();
        keyId = apiKeys.save(new ApiKeyEntity(tenantId, ApiKeyHasher.sha256Hex(RAW_KEY), "local")).getId();
    }

    private void send(ResultMatcher expected) throws Exception {
        mockMvc.perform(post(ProtocolConstants.PATH_EVENTS)
                        .header(ProtocolConstants.HEADER_API_KEY, RAW_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EMPTY_BATCH))
                .andExpect(expected);
    }

    @Test
    void 캐시가_따뜻해도_폐기하면_바로_401() throws Exception {
        send(status().isAccepted());
        send(status().isAccepted()); // 두 번째는 캐시에서

        assertThat(apiKeyService.revoke(keyId)).isTrue();

        send(status().isUnauthorized()); // TTL(60초)을 기다리지 않고 즉시
    }

    @Test
    void 같은_키로_100번_요청해도_DB_조회는_1번() throws Exception {
        long before = resolver.dbLookupCount();

        for (int i = 0; i < 100; i++) {
            send(status().isAccepted());
        }

        assertThat(resolver.dbLookupCount() - before).isBetween(1L, 2L);
    }

    @Test
    void 폐기는_DB에도_남는다() throws Exception {
        send(status().isAccepted());

        apiKeyService.revoke(keyId);

        assertThat(apiKeys.findById(keyId).orElseThrow().isActive()).isFalse();
    }

    @Test
    void 없는_키_id를_폐기하면_false() {
        assertThat(apiKeyService.revoke(999_999L)).isFalse();
    }

    @Test
    void 없는_키는_캐시에_남지_않는다_음성_캐싱_없음() throws Exception {
        String newKey = "issued-later-0001";
        mockMvc.perform(post(ProtocolConstants.PATH_EVENTS)
                        .header(ProtocolConstants.HEADER_API_KEY, newKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EMPTY_BATCH))
                .andExpect(status().isUnauthorized());

        // 방금 401 받은 키를 이제 발급 → 바로 통과해야 한다 (60초 동안 401이면 안 됨)
        Long tenantId = tenants.save(new TenantEntity("나중 발급 팀")).getId();
        apiKeys.save(new ApiKeyEntity(tenantId, ApiKeyHasher.sha256Hex(newKey), "local"));

        mockMvc.perform(post(ProtocolConstants.PATH_EVENTS)
                        .header(ProtocolConstants.HEADER_API_KEY, newKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EMPTY_BATCH))
                .andExpect(status().isAccepted());
        resolver.evict(ApiKeyHasher.sha256Hex(newKey));
    }
}
