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
        notifier = notifierWithLimit(100);

        tenant = new TenantEntity("테스트 팀");
        ReflectionTestUtils.setField(tenant, "id", 1L);
        ReflectionTestUtils.setField(tenant, "discordWebhookLocal", LOCAL_HOOK);
        ReflectionTestUtils.setField(tenant, "discordWebhookProd", PROD_HOOK);
        when(tenants.findById(1L)).thenReturn(Optional.of(tenant));
    }

    /** 분당 상한을 정해서 알림 부품을 새로 만든다. 가짜 디스코드(server)도 새로 묶인다. */
    private DiscordWebhookNotifier notifierWithLimit(int perMinute) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new DiscordWebhookNotifier(tenants, groups, new DiscordEmbedBuilder(),
                new NotifyRateLimiter(perMinute), builder.build());
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
    void choosesWebhookByEnv() {
        givenGroup(20L, 1L, "prod");
        givenGroup(21L, 1L, "local");
        server.expect(requestTo(PROD_HOOK)).andRespond(withNoContent());
        server.expect(requestTo(LOCAL_HOOK)).andRespond(withNoContent());

        notifier.notifyNewGroup(1L, 20L);
        notifier.notifyNewGroup(1L, 21L);

        server.verify();
    }

    @Test
    void notifyLocalOff() {
        ReflectionTestUtils.setField(tenant, "notifyLocal", false);
        givenGroup(22L, 1L, "local");
        givenGroup(23L, 1L, "prod");
        server.expect(requestTo(PROD_HOOK)).andRespond(withNoContent()); // prod 1건만 기대

        notifier.notifyNewGroup(1L, 22L); // local → 안 보냄
        notifier.notifyNewGroup(1L, 23L); // prod → 보냄

        server.verify(); // local 요청이 갔다면 기대하지 않은 요청이라 실패한다
    }

    @Test
    void missingWebhook() {
        ReflectionTestUtils.setField(tenant, "discordWebhookLocal", null);
        ReflectionTestUtils.setField(tenant, "discordWebhookProd", null);
        givenGroup(24L, 1L, "local");
        givenGroup(25L, 1L, "prod");

        assertThatCode(() -> {
            notifier.notifyNewGroup(1L, 24L);
            notifier.notifyNewGroup(1L, 25L);
        }).doesNotThrowAnyException();

        server.verify(); // 요청이 하나라도 가면 MockRestServiceServer가 실패시킨다
    }

    @Test
    void notifyText는_env_채널로_content를_보낸다() {
        server.expect(requestTo(PROD_HOOK))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.content").value("연결 확인"))
                .andRespond(withNoContent());

        notifier.notifyText(1L, "prod", "연결 확인");

        server.verify();
    }

    @Test
    void notifyText는_실패해도_예외를_던지지_않고_주소도_로그에_없다(CapturedOutput output) {
        server.expect(requestTo(LOCAL_HOOK)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatCode(() -> notifier.notifyText(1L, "local", "연결 확인")).doesNotThrowAnyException();

        server.verify();
        assertThat(output).contains("글 전송 실패").doesNotContain("local-secret");
    }

    @Test
    void 분당_상한을_넘은_알림은_버린다() {
        notifier = notifierWithLimit(1);
        givenGroup(26L, 1L, "local");
        givenGroup(27L, 1L, "local");
        server.expect(requestTo(LOCAL_HOOK)).andRespond(withNoContent()); // 1건만 기대

        notifier.notifyNewGroup(1L, 26L);
        notifier.notifyNewGroup(1L, 27L); // 상한 초과 → 버림

        server.verify();
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
