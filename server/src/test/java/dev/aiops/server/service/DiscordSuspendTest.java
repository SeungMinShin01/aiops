package dev.aiops.server.service;

import dev.aiops.server.client.DiscordApiClient;
import dev.aiops.server.client.DiscordApiException;
import dev.aiops.server.client.WebhookInfo;
import dev.aiops.server.model.entity.DiscordStatus;
import dev.aiops.server.model.entity.TenantEntity;
import dev.aiops.server.model.repository.TenantRepository;
import dev.aiops.server.notify.Notifier;
import dev.aiops.server.support.PostgresContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 정지 시 웹훅 삭제·채널 보관, 재개 시 웹훅만 다시 (AI-94). 진짜 DB + 가짜 디스코드.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"aiops.discord.guild-id=111", "aiops.discord.bot-token=fake-bot-token"})
class DiscordSuspendTest extends PostgresContainerSupport {

    // 가짜 웹훅 주소 (진짜 아님, 규칙 10)
    static final String LOCAL_HOOK = "https://discord.com/api/webhooks/1001/fake-local-token";
    static final String PROD_HOOK = "https://discord.com/api/webhooks/1002/fake-prod-token";

    @MockitoBean DiscordApiClient api;
    @MockitoBean Notifier notifier;

    @Autowired DiscordProvisioner provisioner;
    @Autowired TenantRepository tenants;
    @Autowired DataSource dataSource;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired PlatformTransactionManager txManager;

    Long tenantId;

    @BeforeEach
    void setUp() {
        // 채널 자동 생성이 끝난(READY) 팀
        tenantId = tenants.save(new TenantEntity("Shop Team")).getId();
        tenants.updateDiscordRoleId(tenantId, "role-1");
        tenants.updateDiscordCategoryId(tenantId, "cat-1");
        tenants.updateDiscordChannelLocalId(tenantId, "ch-local");
        tenants.updateDiscordChannelProdId(tenantId, "ch-prod");
        tenants.updateDiscordWebhookLocal(tenantId, LOCAL_HOOK);
        tenants.updateDiscordWebhookProd(tenantId, PROD_HOOK);
        tenants.updateDiscordStatus(tenantId, DiscordStatus.READY, null);
    }

    private TenantEntity reload() {
        return tenants.findById(tenantId).orElseThrow();
    }

    private void setTenantStatus(String status) {
        new JdbcTemplate(dataSource).update("UPDATE tenant SET status = ? WHERE id = ?", status, tenantId);
    }

    @Test
    void 정지하면_웹훅_2개만_삭제하고_채널_역할은_남긴다() {
        setTenantStatus("SUSPENDED"); // 13단계 정지 커밋을 흉내

        provisioner.suspend(tenantId);

        verify(api).deleteWebhook("1001"); // url에서 id만 꺼내 삭제
        verify(api).deleteWebhook("1002");
        verify(api, never()).deleteChannel(anyString());
        verify(api, never()).deleteRole(anyString());

        TenantEntity t = reload();
        assertThat(t.getDiscordWebhookLocal()).isNull();
        assertThat(t.getDiscordWebhookProd()).isNull();
        assertThat(t.getDiscordStatus()).isEqualTo(DiscordStatus.SUSPENDED);
        assertThat(t.getDiscordRoleId()).isEqualTo("role-1"); // 보관
        assertThat(t.getDiscordCategoryId()).isEqualTo("cat-1");
        assertThat(t.getDiscordChannelLocalId()).isEqualTo("ch-local");
        assertThat(t.getDiscordChannelProdId()).isEqualTo("ch-prod");
    }

    @Test
    void 재개하면_웹훅만_새로_만들고_READY() {
        setTenantStatus("SUSPENDED");
        provisioner.suspend(tenantId);
        when(api.createWebhook(eq("ch-local"), anyString())).thenReturn(new WebhookInfo("2001", "https://webhook.test/2001/new-l"));
        when(api.createWebhook(eq("ch-prod"), anyString())).thenReturn(new WebhookInfo("2002", "https://webhook.test/2002/new-p"));

        setTenantStatus("ACTIVE"); // 13단계 재개 커밋을 흉내
        DiscordProvisioner.Result result = provisioner.provision(tenantId);

        assertThat(result).isEqualTo(DiscordProvisioner.Result.READY);
        verify(api, times(2)).createWebhook(anyString(), anyString());
        verify(api, never()).createRole(anyString()); // 저장된 id 재사용 — 채널 중복 없음
        verify(api, never()).createCategory(anyString(), anyString());
        verify(api, never()).createTextChannel(anyString(), anyString());
        TenantEntity t = reload();
        assertThat(t.getDiscordStatus()).isEqualTo(DiscordStatus.READY);
        assertThat(t.getDiscordWebhookLocal()).isEqualTo("https://webhook.test/2001/new-l");
    }

    @Test
    void 웹훅_삭제가_실패해도_예외_없이_기록만_남긴다() {
        doThrow(new DiscordApiException(500, "deleteWebhook")).when(api).deleteWebhook("1001");

        provisioner.suspend(tenantId); // 정지는 이미 커밋됐으므로 되돌리지 않는다

        TenantEntity t = reload();
        assertThat(t.getDiscordStatus()).isEqualTo(DiscordStatus.SUSPENDED);
        assertThat(t.getDiscordError()).isEqualTo("suspend-webhook-local:500").doesNotContain("fake-local-token");
    }

    @Test
    void url_형식을_알_수_없으면_디스코드_호출_없이_컬럼만_비운다() {
        tenants.updateDiscordWebhookLocal(tenantId, "not-a-webhook-url");

        provisioner.suspend(tenantId);

        verify(api, never()).deleteWebhook(eq("not-a-webhook-url"));
        verify(api).deleteWebhook("1002");
        assertThat(reload().getDiscordWebhookLocal()).isNull();
    }

    @Test
    void 정지_이벤트가_커밋되면_리스너가_웹훅을_지운다() {
        new TransactionTemplate(txManager).executeWithoutResult(s -> publisher.publishEvent(new TeamSuspended(tenantId)));

        verify(api, timeout(5000)).deleteWebhook("1001"); // 커밋 후 비동기
        verify(api, timeout(5000)).deleteWebhook("1002");
    }

    @Test
    void 롤백되면_정지_처리를_하지_않는다() {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            publisher.publishEvent(new TeamSuspended(tenantId));
            s.setRollbackOnly();
        });

        verify(api, after500ms().never()).deleteWebhook(anyString());
    }

    private static org.mockito.verification.VerificationAfterDelay after500ms() {
        return org.mockito.Mockito.after(500);
    }
}
