package dev.aiops.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.aiops.protocol.ProtocolConstants;
import dev.aiops.server.model.entity.ApiKeyEntity;
import dev.aiops.server.model.entity.TenantEntity;
import dev.aiops.server.model.repository.ApiKeyRepository;
import dev.aiops.server.model.repository.TenantRepository;
import dev.aiops.server.security.ApiKeyHasher;
import dev.aiops.server.support.PostgresContainerSupport;
import jakarta.persistence.EntityManager;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

// 수집 창구(/v1/events)를 진짜 Postgres 로 검사. 테스트가 끝나면 DB 는 자동으로 되돌려짐(롤백)
@SpringBootTest
@Transactional
class IngestControllerTest extends PostgresContainerSupport {

    private static final String RAW_KEY = "test-key-for-ingest";

    @Autowired WebApplicationContext context;
    @Autowired IngestBodySizeFilter bodySizeFilter;   // 413 검문소 (MockMvc 에는 직접 끼워줘야 함)
    @Autowired TenantRepository tenants;
    @Autowired ApiKeyRepository apiKeys;
    @Autowired EntityManager em;

    MockMvc mockMvc;
    Long tenantId;

    // 각 테스트 시작 전: MockMvc 를 만들고, 팀 1개와 그 팀의 키 1개를 DB 에 넣어둠
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(bodySizeFilter)
                .build();
        tenantId = tenants.save(new TenantEntity("테스트 팀")).getId();
        apiKeys.save(new ApiKeyEntity(tenantId, ApiKeyHasher.sha256Hex(RAW_KEY), "local"));
    }

    // 이벤트 n개가 든 묶음 JSON 을 만듦
    private String batchJson(int eventCount) {
        String event = "{\"time\":1700000000000,\"level\":\"ERROR\","
                + "\"exceptionType\":\"java.lang.NullPointerException\","
                + "\"message\":\"boom\",\"thread\":\"main\"}";
        String events = String.join(",", Collections.nCopies(eventCount, event));
        return "{\"env\":\"local\","
                + "\"app\":{\"name\":\"sample\",\"commit\":\"abc\",\"hostHash\":\"abc123def456\"},"
                + "\"events\":[" + events + "],\"droppedCount\":3}";
    }

    @Test
    void 정상_묶음은_202() throws Exception {
        mockMvc.perform(post(ProtocolConstants.PATH_EVENTS)
                        .header(ProtocolConstants.HEADER_API_KEY, RAW_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchJson(2)))
                .andExpect(status().isAccepted());
    }

    @Test
    void 이벤트_100개는_정상_101개는_400() throws Exception {
        mockMvc.perform(post(ProtocolConstants.PATH_EVENTS)
                        .header(ProtocolConstants.HEADER_API_KEY, RAW_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchJson(100)))
                .andExpect(status().isAccepted());

        mockMvc.perform(post(ProtocolConstants.PATH_EVENTS)
                        .header(ProtocolConstants.HEADER_API_KEY, RAW_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchJson(101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("too_many_events"));
    }

    @Test
    void 본문이_1MB를_넘으면_413() throws Exception {
        String big = "x".repeat(1_048_576 + 1);   // 한도(1MB)보다 1바이트 큼. 내용은 JSON 이 아니어도 됨(읽기 전에 거절)
        mockMvc.perform(post(ProtocolConstants.PATH_EVENTS)
                        .header(ProtocolConstants.HEADER_API_KEY, RAW_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(big))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void 키_헤더가_없으면_401() throws Exception {
        mockMvc.perform(post(ProtocolConstants.PATH_EVENTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchJson(1)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_api_key"));
    }

    @Test
    void 잘못된_키는_401() throws Exception {
        mockMvc.perform(post(ProtocolConstants.PATH_EVENTS)
                        .header(ProtocolConstants.HEADER_API_KEY, "wrong-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchJson(1)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 저장하면_ingest_stat이_쌓이고_payload는_원본이_보존되고_heartbeat가_기록된다() throws Exception {
        mockMvc.perform(post(ProtocolConstants.PATH_EVENTS)
                        .header(ProtocolConstants.HEADER_API_KEY, RAW_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchJson(2)))
                .andExpect(status().isAccepted());

        // ingest_stat: 받은 2, 버린 3
        Object[] stat = (Object[]) em.createNativeQuery(
                        "select received_count, dropped_count from ingest_stat where tenant_id = :t and env = 'local'")
                .setParameter("t", tenantId)
                .getSingleResult();
        assertThat(((Number) stat[0]).longValue()).isEqualTo(2);
        assertThat(((Number) stat[1]).longValue()).isEqualTo(3);

        // payload: DB 에서 JSON 필드를 꺼낼 수 있어야 함 (원본 보존)
        Object type = em.createNativeQuery(
                        "select payload->>'exceptionType' from error_event where tenant_id = :t limit 1")
                .setParameter("t", tenantId)
                .getSingleResult();
        assertThat(type).isEqualTo("java.lang.NullPointerException");

        // heartbeat_last: 이 호스트의 행이 생김
        Number heartbeatRows = (Number) em.createNativeQuery(
                        "select count(*) from heartbeat_last where tenant_id = :t and host_hash = 'abc123def456'")
                .setParameter("t", tenantId)
                .getSingleResult();
        assertThat(heartbeatRows.longValue()).isEqualTo(1);
    }
}