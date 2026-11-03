package dev.aiops.server.service;

import dev.aiops.server.model.entity.ApiKeyEntity;
import dev.aiops.server.model.repository.ApiKeyRepository;
import dev.aiops.server.security.ApiKeyResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 팀 API 키 관리 (AI-74에서 revoke만, 발급·목록 API는 14단계가 붙인다).
 */
@Service
public class ApiKeyService {

    private final ApiKeyRepository apiKeys;
    private final ApiKeyResolver resolver;

    public ApiKeyService(ApiKeyRepository apiKeys, ApiKeyResolver resolver) {
        this.apiKeys = apiKeys;
        this.resolver = resolver;
    }

    /**
     * 키 폐기: revoked_at 저장 → <b>커밋이 끝난 뒤</b> 캐시에서 지운다.
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
        return true;
    }
}
