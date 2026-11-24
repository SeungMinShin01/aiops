package dev.aiops.server.service;

import dev.aiops.server.dto.ApiKeySummary;
import dev.aiops.server.dto.KeyIssued;
import dev.aiops.server.model.entity.ApiKeyEntity;
import dev.aiops.server.model.repository.ApiKeyRepository;
import dev.aiops.server.model.repository.TenantRepository;
import dev.aiops.server.security.ApiKeyHasher;
import dev.aiops.server.security.ApiKeyResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * 팀 API 키 발급·폐기·목록 (AI-74 폐기 + AI-88). 키 로직은 이 서비스 한 곳.
 * <p>
 * D-092: MVP에서는 관리자만 발급·폐기한다 (관리자 컨트롤러가 이것만 부른다). v1.0 셀프 온보딩 때
 * 조장 엔드포인트를 붙여도 같은 서비스를 부르면 되도록 컨트롤러와 분리해 둔다.
 * 원문은 발급 응답 1회만, DB에는 SHA-256 해시(ApiKeyHasher — 수집 인증과 같은 함수)만 (규칙 10).
 * api_key는 RLS가 없어서 팀 기준 조회·폐기에는 tenant_id 조건을 고정한다 (D-023).
 */
@Service
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);
    private static final Set<String> ENVS = Set.of("local", "prod");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    /** 목록 표시용 앞자리 = "aiops_l_" + 무작위 4자 (12자, 원문 복원 불가) */
    static final int PREFIX_LENGTH = 12;

    private final ApiKeyRepository apiKeys;
    private final TenantRepository tenants;
    private final ApiKeyResolver resolver;

    public ApiKeyService(ApiKeyRepository apiKeys, TenantRepository tenants, ApiKeyResolver resolver) {
        this.apiKeys = apiKeys;
        this.tenants = tenants;
        this.resolver = resolver;
    }

    /**
     * 키 발급. 원문 = "aiops_" + env 한 글자 + "_" + SecureRandom 24바이트 base64url (32자).
     *
     * @throws AdminApiException 400 env가 local/prod가 아님, 404 팀 없음
     */
    @Transactional
    public KeyIssued issue(Long tenantId, String env, Long createdBy) {
        if (env == null || !ENVS.contains(env)) {
            throw new AdminApiException(HttpStatus.BAD_REQUEST, "INVALID_ENV", "env는 local 또는 prod");
        }
        if (!tenants.existsById(tenantId)) {
            throw new AdminApiException(HttpStatus.NOT_FOUND, "TEAM_NOT_FOUND", "팀이 없습니다");
        }
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String raw = "aiops_" + env.charAt(0) + "_" + ENCODER.encodeToString(bytes);

        ApiKeyEntity saved = apiKeys.save(new ApiKeyEntity(tenantId, ApiKeyHasher.sha256Hex(raw), env,
                raw.substring(0, PREFIX_LENGTH), createdBy));
        log.info("키 발급 teamId={} env={} keyId={} by={}", tenantId, env, saved.getId(), createdBy);   // 원문 없음
        return new KeyIssued(saved.getId(), env, raw);
    }

    /** 팀 키 목록 (원문·해시 없음, 최근 발급 순) */
    @Transactional(readOnly = true)
    public List<ApiKeySummary> list(Long tenantId) {
        return apiKeys.findAllByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .map(k -> new ApiKeySummary(k.getId(), k.getEnv(), k.getKeyPrefix(), k.getCreatedAt(),
                        k.getRevokedAt(), k.getLastUsedAt()))
                .toList();
    }

    /**
     * 그 팀의 키 폐기 (AI-88). 다른 팀 키 id면 false → 404.
     */
    @Transactional
    public boolean revoke(Long tenantId, Long keyId) {
        if (apiKeys.findByIdAndTenantId(keyId, tenantId).isEmpty()) {
            return false;
        }
        return revoke(keyId);
    }

    /**
     * 키 폐기: revoked_at 저장 → <b>커밋이 끝난 뒤</b> 캐시에서 지운다 (AI-74).
     * 순서가 반대면 커밋 전에 들어온 요청이 옛 값으로 캐시를 다시 채운다.
     * DB와 캐시를 둘 다 처리한다 (반쪽 처리 금지).
     *
     * @return 키가 있었으면 true (이미 폐기된 키도 true — 캐시는 다시 지운다)
     */
    @Transactional
    public boolean revoke(Long keyId) {
        ApiKeyEntity key = apiKeys.findById(keyId).orElse(null);
        if (key == null) {
            return false;
        }
        String keyHash = apiKeys.findKeyHashById(keyId).orElseThrow();
        key.revoke();
        apiKeys.save(key);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                resolver.evict(keyHash);
            }
        });
        log.info("키 폐기 teamId={} keyId={}", key.getTenantId(), keyId);
        return true;
    }
}
