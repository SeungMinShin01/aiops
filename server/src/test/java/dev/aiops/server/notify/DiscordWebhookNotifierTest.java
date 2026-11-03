package dev.aiops.server.notify;

import dev.aiops.server.model.entity.ErrorGroupEntity;
import dev.aiops.server.model.entity.TenantEntity;
import dev.aiops.server.model.repository.ErrorGroupRepository;
import dev.aiops.server.model.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

@ExtendWith(OutputCaptureExtension.class)
class DiscordWebhookNotifierTest {

    // 진짜 디스코드 주소가 아닌 가짜 주소 (규칙 10)
    static final String LOCAL_HOOK = "https://webhook.test/hooks/local-secret";
    static final String PROD_HOOK = "https://webhook.test/hooks/prod-secret";

    TenantRepository tenants = mock(TenantRepository.class);
    ErrorGroupRepository groups = mock(ErrorGroupRepository.class);
    MockRestServiceServer server;
    DiscordWebhookNotifier notifier;
    TenantEntity tenant;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        notifier = new DiscordWebhookNotifier(tenants, groups, new DiscordEmbedBuilder(), builder.build());

        tenant = new TenantEntity("테스트 팀");
        ReflectionTestUtils.setField(tenant, "id", 1L);
        ReflectionTestUtils.setField(tenant, "discordWebhookLocal", LOCAL_HOOK);
        ReflectionTestUtils.setField(tenant, "discordWebhookProd", PROD_HOOK);
        when(tenants.findById(1L)).thenReturn(Optional.of(tenant));
    }

    private void givenGroup(long groupId, long tenantId, String env) {
        ErrorGroupEntity g = new ErrorGroupEntity(tenantId, "f".repeat(64),
                "java.lang.NullPointerException", "com.team.UserService.find(UserService.java)", env);
        ReflectionTestUtils.setField(g, "id", groupId);
        when(groups.findById(groupId)).thenReturn(Optional.of(g));
    }

    @Test
    void local_그룹은_local_웹훅으로_embed를_보낸다() {
        givenGroup(10L, 1L, "local");
        server.expect(requestTo(LOCAL_HOOK))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.embeds[0].title").value("새 에러 · NullPointerException"))
                .andExpect(jsonPath("$.embeds[0].fields[1].name").value("env"))
                .andExpect(jsonPath("$.embeds[0].fields[1].value").value("local"))
                .andRespond(withNoContent());

        notifier.notifyNewGroup(1L, 10L);

        server.verify();
    }

    @Test
    void prod_그룹은_prod_웹훅으로_보낸다() {
        givenGroup(11L, 1L, "prod");
        server.expect(requestTo(PROD_HOOK)).andRespond(withNoContent());

        notifier.notifyNewGroup(1L, 11L);

        server.verify();
    }

    @Test
    void 웹훅이_없으면_보내지_않는다() {
        ReflectionTestUtils.setField(tenant, "discordWebhookLocal", null);
        givenGroup(12L, 1L, "local");

        notifier.notifyNewGroup(1L, 12L);

        server.verify(); // 요청이 하나라도 가면 MockRestServiceServer가 실패시킨다
    }

    @Test
    void 다른_팀_그룹이면_보내지_않는다() {
        givenGroup(13L, 2L, "local");

        notifier.notifyNewGroup(1L, 13L);

        server.verify();
    }

    @Test
    void 그룹이_없으면_보내지_않는다() {
        when(groups.findById(99L)).thenReturn(Optional.empty());

        assertThatCode(() -> notifier.notifyNewGroup(1L, 99L)).doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void 디스코드가_에러를_돌려줘도_예외를_던지지_않고_주소도_로그에_없다(CapturedOutput output) {
        givenGroup(14L, 1L, "local");
        server.expect(requestTo(LOCAL_HOOK)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatCode(() -> notifier.notifyNewGroup(1L, 14L)).doesNotThrowAnyException();

        server.verify();
        assertThat(output).contains("알림 실패").contains("500");
        assertThat(output).doesNotContain("local-secret").doesNotContain("webhook.test");
    }

    @Test
    void 타임아웃이어도_예외를_던지지_않고_주소도_로그에_없다(CapturedOutput output) {
        givenGroup(15L, 1L, "local");
        server.expect(requestTo(LOCAL_HOOK)).andRespond(withException(new SocketTimeoutException("timeout")));

        assertThatCode(() -> notifier.notifyNewGroup(1L, 15L)).doesNotThrowAnyException();

        server.verify();
        assertThat(output).contains("알림 실패");
        assertThat(output).doesNotContain("local-secret").doesNotContain("webhook.test");
    }
}
