package dev.aiops.server.notify;

import dev.aiops.server.model.entity.ErrorGroupEntity;
import dev.aiops.server.model.entity.TenantEntity;
import dev.aiops.server.model.entity.TenantStatus;
import dev.aiops.server.model.repository.ErrorGroupRepository;
import dev.aiops.server.model.repository.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * 새 그룹 → 팀·env 디스코드 채널로 웹훅 embed 전송 (⑯).
 * <p>
 * 웹훅 주소는 "아는 사람은 누구나 보낼 수 있는 비밀값"이다 (규칙 10).
 * 로그에는 tenantId·env·HTTP 상태만 남기고, 예외 객체·메시지는 넘기지 않는다
 * (RestClient 예외 메시지에는 요청 주소가 들어 있다).
 */
@Component
public class DiscordWebhookNotifier implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(DiscordWebhookNotifier.class);
    static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final TenantRepository tenants;
    private final ErrorGroupRepository groups;
    private final DiscordEmbedBuilder embedBuilder;
    private final NotifyRateLimiter rateLimiter;
    private final RestClient restClient;

    @Autowired
    public DiscordWebhookNotifier(TenantRepository tenants, ErrorGroupRepository groups,
            DiscordEmbedBuilder embedBuilder, NotifyRateLimiter rateLimiter) {
        this(tenants, groups, embedBuilder, rateLimiter,
                RestClient.builder().requestFactory(timeoutFactory()).build());
    }

    /** 테스트에서 MockRestServiceServer에 묶은 RestClient를 넣기 위한 생성자. */
    DiscordWebhookNotifier(TenantRepository tenants, ErrorGroupRepository groups,
            DiscordEmbedBuilder embedBuilder, NotifyRateLimiter rateLimiter, RestClient restClient) {
        this.tenants = tenants;
        this.groups = groups;
        this.embedBuilder = embedBuilder;
        this.rateLimiter = rateLimiter;
        this.restClient = restClient;
    }

    private static SimpleClientHttpRequestFactory timeoutFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(TIMEOUT);
        factory.setReadTimeout(TIMEOUT);
        return factory;
    }

    @Override
    public void notifyNewGroup(Long tenantId, Long groupId) {
        String env = null;
        try {
            ErrorGroupEntity group = groups.findById(groupId).orElse(null);
            if (group == null || !group.getTenantId().equals(tenantId)) {
                log.warn("알림 생략: 그룹 없음 tenantId={} groupId={}", tenantId, groupId);
                return;
            }
            env = group.getFirstEnv();

            // 1. 채널 선택: env에 맞는 웹훅 (15단계 전까지는 로컬 DB에 psql로 직접 넣는다)
            //    env는 그룹의 env = 키의 env (Starter가 보낸 값이 아니라 수집 단계에서 키로 정한 값)
            TenantEntity tenant = tenants.findById(tenantId).orElse(null);
            // 정지된 팀에는 보내지 않는다 (웹훅이 남아 있어도, AI-94)
            if (tenant != null && tenant.getStatus() == TenantStatus.SUSPENDED) {
                log.info("알림 생략: 정지된 팀 tenantId={} env={}", tenantId, env);
                return;
            }
            String webhook = (tenant == null) ? null : webhookFor(tenant, env);
            if (webhook == null || webhook.isBlank()) {
                log.info("알림 생략: 웹훅 미설정 tenantId={} env={}", tenantId, env);
                return;
            }
            // local 알림은 팀이 끌 수 있다. prod는 항상 보낸다 (prod 끄기는 범위 밖)
            if ("local".equals(env) && !tenant.isNotifyLocal()) {
                log.debug("알림 생략: local 알림 꺼짐 tenantId={}", tenantId);
                return;
            }

            // 2. 팀별 분당 상한. 넘으면 버린다 (큐에 모아 두지 않음)
            if (!rateLimiter.tryAcquire(tenantId)) {
                return;
            }

            // 3. embed 조립 후 전송
            Map<String, Object> body = embedBuilder.build(group);
            restClient.post()
                    .uri(URI.create(webhook))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("알림 전송 tenantId={} env={} groupId={}", tenantId, env, groupId);

        } catch (RestClientResponseException e) {
            log.warn("알림 실패: 디스코드 응답 {} tenantId={} env={}", e.getStatusCode().value(), tenantId, env);
        } catch (ResourceAccessException e) {
            log.warn("알림 실패: 연결 오류·타임아웃 tenantId={} env={}", tenantId, env);
        } catch (RuntimeException e) {
            log.warn("알림 실패: {} tenantId={} env={}", e.getClass().getSimpleName(), tenantId, env);
        }
    }

    @Override
    public void notifyText(Long tenantId, String env, String text) {
        try {
            TenantEntity tenant = tenants.findById(tenantId).orElse(null);
            String webhook = (tenant == null) ? null : webhookFor(tenant, env);
            if (webhook == null || webhook.isBlank()) {
                log.info("글 전송 생략: 웹훅 미설정 tenantId={} env={}", tenantId, env);
                return;
            }
            restClient.post()
                    .uri(URI.create(webhook))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("content", text))
                    .retrieve()
                    .toBodilessEntity();
            log.info("글 전송 tenantId={} env={}", tenantId, env);
        } catch (RestClientResponseException e) {
            log.warn("글 전송 실패: 디스코드 응답 {} tenantId={} env={}", e.getStatusCode().value(), tenantId, env);
        } catch (ResourceAccessException e) {
            log.warn("글 전송 실패: 연결 오류·타임아웃 tenantId={} env={}", tenantId, env);
        } catch (RuntimeException e) {
            log.warn("글 전송 실패: {} tenantId={} env={}", e.getClass().getSimpleName(), tenantId, env);
        }
    }

    static String webhookFor(TenantEntity tenant, String env) {
        if ("local".equals(env)) {
            return tenant.getDiscordWebhookLocal();
        }
        if ("prod".equals(env)) {
            return tenant.getDiscordWebhookProd();
        }
        return null;
    }
}
