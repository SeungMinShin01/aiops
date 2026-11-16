package dev.aiops.server.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;

import dev.aiops.server.model.entity.ErrorGroupEntity;
import dev.aiops.server.model.entity.GroupStatus;
import dev.aiops.server.model.entity.TenantEntity;
import dev.aiops.server.model.repository.ErrorGroupRepository;
import dev.aiops.server.model.repository.TenantRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

// AI-97 : 재발 알림의 제목, 그리고 무시(IGNORED)한 그룹은 알리지 않는 것. DiscordWebhookNotifierTest 와 같은 방식 (가짜 디스코드)
class DiscordReopenNotifyTest {

    static final String LOCAL_HOOK = "https://webhook.test/hooks/local-secret";   // 가짜 주소 (규칙 10)

    TenantRepository tenants = mock(TenantRepository.class);
    ErrorGroupRepository groups = mock(ErrorGroupRepository.class);
    MockRestServiceServer server;
    DiscordWebhookNotifier notifier;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        notifier = new DiscordWebhookNotifier(tenants, groups, new DiscordEmbedBuilder(),
                new NotifyRateLimiter(100), builder.build());

        TenantEntity tenant = new TenantEntity("테스트 팀");
        ReflectionTestUtils.setField(tenant, "id", 1L);
        ReflectionTestUtils.setField(tenant, "discordWebhookLocal", LOCAL_HOOK);
        when(tenants.findById(1L)).thenReturn(Optional.of(tenant));
    }

    private void givenGroup(long groupId, GroupStatus status) {
        ErrorGroupEntity g = new ErrorGroupEntity(1L, "f".repeat(64), "java.lang.NullPointerException",
                "com.team.UserService.find(UserService.java)", "local");
        ReflectionTestUtils.setField(g, "id", groupId);
        ReflectionTestUtils.setField(g, "status", status);
        when(groups.findById(groupId)).thenReturn(Optional.of(g));
    }

    @Test
    void 다시_열린_그룹은_재발_제목으로_보낸다() {
        givenGroup(10L, GroupStatus.OPEN);
        server.expect(requestTo(LOCAL_HOOK))
                .andExpect(jsonPath("$.embeds[0].title").value("재발 에러 · NullPointerException"))
                .andRespond(withNoContent());

        notifier.notifyReopenedGroup(1L, 10L);

        server.verify();
    }

    @Test
    void 새_그룹_제목은_그대로다() {
        givenGroup(11L, GroupStatus.OPEN);
        server.expect(requestTo(LOCAL_HOOK))
                .andExpect(jsonPath("$.embeds[0].title").value("새 에러 · NullPointerException"))
                .andRespond(withNoContent());

        notifier.notifyNewGroup(1L, 11L);

        server.verify();
    }

    @Test
    void 무시한_그룹은_알리지_않는다() {
        givenGroup(12L, GroupStatus.IGNORED);   // 기대한 요청이 없으므로 요청이 가면 실패한다

        notifier.notifyReopenedGroup(1L, 12L);
        notifier.notifyNewGroup(1L, 12L);

        server.verify();
    }

    @Test
    void 재발_제목은_embed_builder_에서도_바뀐다() {
        ErrorGroupEntity g = new ErrorGroupEntity(1L, "f".repeat(64), "java.lang.IllegalStateException", null, "prod");

        Object embeds = new DiscordEmbedBuilder().build(g, true).get("embeds");

        assertThat(embeds.toString()).contains("재발 에러 · IllegalStateException");
    }
}