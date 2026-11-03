package dev.aiops.server.service;

import dev.aiops.server.client.DiscordApiClient;
import dev.aiops.server.client.DiscordApiException;
import dev.aiops.server.client.WebhookInfo;
import dev.aiops.server.config.DiscordProperties;
import dev.aiops.server.model.entity.DiscordStatus;
import dev.aiops.server.model.entity.TenantEntity;
import dev.aiops.server.model.entity.TenantStatus;
import dev.aiops.server.model.repository.TenantRepository;
import dev.aiops.server.notify.Notifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 팀 전용 디스코드 자원을 순서대로 만든다: 역할 → 카테고리 → #local·#prod 채널 → 웹훅 2개 (AI-92, D-069).
 * <p>
 * <b>멱등</b>: 자원마다 "저장된 id가 있으면 건너뛰고, 없으면 만든 뒤 즉시 저장". 기준은 이름이 아니라 저장된 id다.
 * 몇 번을 돌려도 1세트, 중간에 실패해도 다음 실행은 없는 것만 만든다. 실패해도 이미 만든 자원은 지우지 않는다.
 * <p>
 * 정책(무엇을 언제)은 여기, REST 호출은 DiscordApiClient. 예외는 밖으로 던지지 않는다 — 결과는 Result와 discord_status로.
 * 웹훅 url은 tenant 컬럼에만 저장하고 로그·discord_error에 쓰지 않는다 (규칙 40).
 */
@Service
public class DiscordProvisioner {

    private static final Logger log = LoggerFactory.getLogger(DiscordProvisioner.class);

    static final String WEBHOOK_NAME = "aiops";
    static final String TEST_MESSAGE = "✅ AIOps 연결 확인 — 이 채널로 새 에러 알림이 옵니다.";
    /** 디스코드 채널 이름은 최대 100자. "-local"을 붙일 자리를 남긴다 */
    static final int SLUG_MAX = 80;

    public enum Result {
        /** 이번 실행으로 전부 갖춰짐 */
        READY,
        /** 어느 단계에서 실패 → discord_status=FAILED, 재시도하면 이어서 만든다 */
        FAILED,
        /** 이미 READY라 아무것도 안 함 */
        ALREADY_READY,
        /** 정지된 팀 — 웹훅이 다시 생기면 안 된다 */
        SUSPENDED,
        /** 같은 팀을 다른 스레드가 만드는 중 (재시도 버튼 연타) */
        IN_PROGRESS,
        NOT_FOUND,
        /** 봇 토큰·서버 id 환경변수가 없음 (로컬 등) */
        NOT_CONFIGURED
    }

    private final TenantRepository tenants;
    private final DiscordApiClient api;
    private final Notifier notifier;
    private final DiscordProperties properties;
    private final Map<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

    public DiscordProvisioner(TenantRepository tenants, DiscordApiClient api, Notifier notifier,
            DiscordProperties properties) {
        this.tenants = tenants;
        this.api = api;
        this.notifier = notifier;
        this.properties = properties;
    }

    public Result provision(Long tenantId) {
        if (!properties.isBotConfigured()) {
            log.info("디스코드 채널 생성 생략: 봇 설정 없음 tenantId={}", tenantId);
            return Result.NOT_CONFIGURED;
        }
        ReentrantLock lock = locks.computeIfAbsent(tenantId, id -> new ReentrantLock());
        if (!lock.tryLock()) {
            log.info("디스코드 채널 생성 진행 중 tenantId={}", tenantId);
            return Result.IN_PROGRESS;
        }
        try {
            return doProvision(tenantId);
        } finally {
            lock.unlock();
        }
    }

    private Result doProvision(Long tenantId) {
        TenantEntity tenant = tenants.findById(tenantId).orElse(null);
        if (tenant == null) {
            log.warn("디스코드 채널 생성 생략: 팀 없음 tenantId={}", tenantId);
            return Result.NOT_FOUND;
        }
        if (tenant.getStatus() == TenantStatus.SUSPENDED) {
            log.info("디스코드 채널 생성 생략: 정지된 팀 tenantId={}", tenantId);
            return Result.SUSPENDED;
        }
        if (tenant.getDiscordStatus() == DiscordStatus.READY) {
            return Result.ALREADY_READY;
        }

        String slug = slug(tenant.getName(), tenantId);
        String step = "role";
        try {
            String roleId = tenant.getDiscordRoleId();
            if (isBlank(roleId)) {
                roleId = api.createRole("team-" + slug);
                tenants.updateDiscordRoleId(tenantId, roleId);
            }

            step = "category";
            String categoryId = tenant.getDiscordCategoryId();
            if (isBlank(categoryId)) {
                categoryId = api.createCategory(slug, roleId);
                tenants.updateDiscordCategoryId(tenantId, categoryId);
            }

            step = "channel-local";
            String localChannel = tenant.getDiscordChannelLocalId();
            if (isBlank(localChannel)) {
                localChannel = api.createTextChannel(slug + "-local", categoryId);
                tenants.updateDiscordChannelLocalId(tenantId, localChannel);
            }

            step = "channel-prod";
            String prodChannel = tenant.getDiscordChannelProdId();
            if (isBlank(prodChannel)) {
                prodChannel = api.createTextChannel(slug + "-prod", categoryId);
                tenants.updateDiscordChannelProdId(tenantId, prodChannel);
            }

            step = "webhook-local";
            if (isBlank(tenant.getDiscordWebhookLocal())) {
                WebhookInfo hook = api.createWebhook(localChannel, WEBHOOK_NAME);
                tenants.updateDiscordWebhookLocal(tenantId, hook.url());
            }

            step = "webhook-prod";
            if (isBlank(tenant.getDiscordWebhookProd())) {
                WebhookInfo hook = api.createWebhook(prodChannel, WEBHOOK_NAME);
                tenants.updateDiscordWebhookProd(tenantId, hook.url());
            }

            tenants.updateDiscordStatus(tenantId, DiscordStatus.READY, null);
            log.info("디스코드 채널 생성 완료 tenantId={}", tenantId);

            // 연결 확인 메시지 (실패해도 Notifier가 로그만 남긴다)
            notifier.notifyText(tenantId, "local", TEST_MESSAGE);
            notifier.notifyText(tenantId, "prod", TEST_MESSAGE);
            return Result.READY;

        } catch (DiscordApiException e) {
            return fail(tenantId, step, String.valueOf(e.getStatus()));
        } catch (RuntimeException e) {
            return fail(tenantId, step, e.getClass().getSimpleName());
        }
    }

    private Result fail(Long tenantId, String step, String reason) {
        String error = step + ":" + reason;
        if (error.length() > 100) {
            error = error.substring(0, 100);
        }
        try {
            tenants.updateDiscordStatus(tenantId, DiscordStatus.FAILED, error);
        } catch (RuntimeException ignored) {
            // 상태 저장까지 실패해도 밖으로 던지지 않는다 (팀 생성은 이미 성공)
        }
        log.warn("디스코드 채널 생성 실패 tenantId={} 단계={} 원인={}", tenantId, step, reason);
        return Result.FAILED;
    }

    /**
     * 팀 이름 → 디스코드 이름용 slug. 소문자, 공백 → '-', 글자·숫자·'-'·'_'만 남긴다 (한글 가능).
     * 남는 게 없으면 "team-<id>".
     */
    static String slug(String name, Long tenantId) {
        String s = (name == null) ? "" : name.trim().toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (Character.isWhitespace(cp) || cp == '-') {
                out.append('-');
            } else if (Character.isLetterOrDigit(cp) || cp == '_') {
                out.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        String slug = out.toString().replaceAll("-{2,}", "-").replaceAll("^-|-$", "");
        if (slug.length() > SLUG_MAX) {
            slug = slug.substring(0, SLUG_MAX).replaceAll("-$", "");
        }
        return slug.isEmpty() ? "team-" + tenantId : slug;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
