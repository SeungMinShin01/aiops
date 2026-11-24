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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * DiscordProvisioner (AI-92). 진짜 DB(tenant 저장)는 그대로 쓰고, 디스코드는 가짜(DiscordApiClient 목)로 바꾼다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"aiops.discord.guild-id=111", "aiops.discord.bot-token=fake-bot-token"})
class DiscordProvisionerTest extends PostgresContainerSupport {

    // 가짜 웹훅 주소 (진짜 아님, 규칙 10)
    static final String LOCAL_HOOK = "https://webhook.test/hooks/local-secret";
    static final String PROD_HOOK = "https://webhook.test/hooks/prod-secret";

    @MockitoBean
    DiscordApiClient api;
    @MockitoBean
    Notifier notifier;

    @Autowired
    DiscordProvisioner provisioner;
    @Autowired
    TenantRepository tenants;
    @Autowired
    DataSource dataSource;

    private Long tenantId;

    @BeforeEach
    void setUp() {
        tenantId = tenants.save(new TenantEntity("Shop Team")).getId();

        when(api.createRole("team-shop-team")).thenReturn("role-1");
        when(api.createCategory("shop-team", "role-1")).thenReturn("cat-1");
        when(api.createTextChannel("shop-team-local", "cat-1")).thenReturn("ch-local");
        when(api.createTextChannel("shop-team-prod", "cat-1")).thenReturn("ch-prod");
        when(api.createWebhook(eq("ch-local"), anyString())).thenReturn(new WebhookInfo("w-local", LOCAL_HOOK));
        when(api.createWebhook(eq("ch-prod"), anyString())).thenReturn(new WebhookInfo("w-prod", PROD_HOOK));
    }

    private TenantEntity reload() {
        return tenants.findById(tenantId).orElseThrow();
    }

    @Test
    void 역할_카테고리_채널2_웹훅2를_만들고_id를_저장한다() {
        DiscordProvisioner.Result result = provisioner.provision(tenantId);

        assertThat(result).isEqualTo(DiscordProvisioner.Result.READY);
        TenantEntity t = reload();
        assertThat(t.getDiscordRoleId()).isEqualTo("role-1");
        assertThat(t.getDiscordCategoryId()).isEqualTo("cat-1");
        assertThat(t.getDiscordChannelLocalId()).isEqualTo("ch-local");
        assertThat(t.getDiscordChannelProdId()).isEqualTo("ch-prod");
        assertThat(t.getDiscordWebhookLocal()).isEqualTo(LOCAL_HOOK);
        assertThat(t.getDiscordWebhookProd()).isEqualTo(PROD_HOOK);
        assertThat(t.getDiscordStatus()).isEqualTo(DiscordStatus.READY);
        assertThat(t.getDiscordError()).isNull();
        verify(notifier).notifyText(eq(tenantId), eq("local"), anyString());
        verify(notifier).notifyText(eq(tenantId), eq("prod"), anyString());
    }

    @Test
    void 두_번_실행해도_자원은_1세트() {
        provisioner.provision(tenantId);
        DiscordProvisioner.Result second = provisioner.provision(tenantId);

        assertThat(second).isEqualTo(DiscordProvisioner.Result.ALREADY_READY);
        verify(api, times(1)).createRole(anyString());
        verify(api, times(1)).createCategory(anyString(), anyString());
        verify(api, times(2)).createTextChannel(anyString(), anyString()); // local + prod
        verify(api, times(2)).createWebhook(anyString(), anyString());
    }

    @Test
    void 채널_단계에서_실패하면_FAILED_앞_단계_id는_남는다() {
        when(api.createTextChannel("shop-team-local", "cat-1"))
                .thenThrow(new DiscordApiException(500, "createTextChannel"));

        DiscordProvisioner.Result result = provisioner.provision(tenantId);

        assertThat(result).isEqualTo(DiscordProvisioner.Result.FAILED);
        TenantEntity t = reload();
        assertThat(t.getDiscordStatus()).isEqualTo(DiscordStatus.FAILED);
        assertThat(t.getDiscordError()).isEqualTo("channel-local:500");
        assertThat(t.getDiscordRoleId()).isEqualTo("role-1"); // 실패해도 이미 만든 건 지우지 않고 저장돼 있다
        assertThat(t.getDiscordCategoryId()).isEqualTo("cat-1");
        assertThat(t.getDiscordChannelLocalId()).isNull();
        assertThat(t.getDiscordWebhookLocal()).isNull();
        verify(notifier, never()).notifyText(eq(tenantId), anyString(), anyString());
    }

    @Test
    void 실패_후_재실행하면_없는_것만_만든다() {
        when(api.createTextChannel("shop-team-local", "cat-1"))
                .thenThrow(new DiscordApiException(500, "createTextChannel"))
                .thenReturn("ch-local");
        provisioner.provision(tenantId); // 실패

        DiscordProvisioner.Result retry = provisioner.provision(tenantId);

        assertThat(retry).isEqualTo(DiscordProvisioner.Result.READY);
        verify(api, times(1)).createRole(anyString()); // 역할·카테고리는 다시 안 만든다
        verify(api, times(1)).createCategory(anyString(), anyString());
        verify(api, times(2)).createTextChannel(eq("shop-team-local"), anyString()); // 실패 1 + 재시도 1
        verify(api, times(1)).createTextChannel(eq("shop-team-prod"), anyString());
        assertThat(reload().getDiscordError()).isNull();
    }

    @Test
    void 이미_READY면_디스코드를_부르지_않는다() {
        tenants.updateDiscordStatus(tenantId, DiscordStatus.READY, null);

        assertThat(provisioner.provision(tenantId)).isEqualTo(DiscordProvisioner.Result.ALREADY_READY);
        verifyNoInteractions(api);
    }

    @Test
    void 정지된_팀은_만들지_않는다() {
        new JdbcTemplate(dataSource).update("UPDATE tenant SET status = 'SUSPENDED' WHERE id = ?", tenantId);

        assertThat(provisioner.provision(tenantId)).isEqualTo(DiscordProvisioner.Result.SUSPENDED);
        verifyNoInteractions(api);
    }

    @Test
    void 같은_팀을_동시에_실행하면_하나만_돈다() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(api.createRole("team-shop-team")).thenAnswer(inv -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return "role-1";
        });

        CompletableFuture<DiscordProvisioner.Result> first = CompletableFuture.supplyAsync(() -> provisioner.provision(tenantId));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        DiscordProvisioner.Result second = provisioner.provision(tenantId); // 첫 번째가 역할 만드는 중
        release.countDown();

        assertThat(second).isEqualTo(DiscordProvisioner.Result.IN_PROGRESS);
        assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(DiscordProvisioner.Result.READY);
        verify(api, times(1)).createRole(anyString());
    }

    @Test
    void 실패_기록에는_웹훅_주소가_없다() {
        when(api.createWebhook(eq("ch-prod"), anyString())).thenThrow(new DiscordApiException(429, "createWebhook"));

        provisioner.provision(tenantId);

        String error = reload().getDiscordError();
        assertThat(error).isEqualTo("webhook-prod:429");
        assertThat(error).doesNotContain("webhook.test").doesNotContain("secret");
    }

    @Test
    void slug_규칙() {
        assertThat(DiscordProvisioner.slug("Shop Team", 1L)).isEqualTo("shop-team");
        assertThat(DiscordProvisioner.slug("  My   Shop!! 2 ", 1L)).isEqualTo("my-shop-2");
        assertThat(DiscordProvisioner.slug("쇼핑몰 팀", 1L)).isEqualTo("쇼핑몰-팀");
        assertThat(DiscordProvisioner.slug("!!!", 7L)).isEqualTo("team-7");
        assertThat(DiscordProvisioner.slug(null, 7L)).isEqualTo("team-7");
        assertThat(DiscordProvisioner.slug("a".repeat(200), 1L)).hasSizeLessThanOrEqualTo(DiscordProvisioner.SLUG_MAX);
    }
}
