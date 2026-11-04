package dev.aiops.server.security;

import dev.aiops.server.model.entity.ApiKeyEntity;
import dev.aiops.server.model.repository.ApiKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

// 요청 헤더의 키 원문 → "어느 팀, 어느 env" 로 바꾸는 곳. 인증 판단은 이 클래스 한 곳에만 둠
// 폐기 키 거부와 캐시 무효화도 여기 한 곳 (AI-74, v0.2 2-4). 다른 곳에 키 검증을 복제하지 않는다
@Component // 스프링이 이 클래스의 객체를 만들어서 관리 (ApiKeyAuthFilter 가 가져다 씀)
public class ApiKeyResolver {
    // 이 클래스에서 로그를 찍는 도구
    private static final Logger log = LoggerFactory.getLogger(ApiKeyResolver.class);
    // DB에서 키를 찾아주는 도구. final 이라 한 번 넣으면 바뀌지 않음
    private final ApiKeyRepository apiKeyRepository;
    // 해시 → (팀, env) 짧은 캐시 (AI-74). 인증에 성공한 키만 들어간다
    private final ApiKeyCache cache;
    // DB 조회 횟수 (캐시가 일하는지 확인용)
    private final AtomicLong dbLookups = new AtomicLong();

    // 스프링이 ApiKeyRepository·ApiKeyCache 를 만들어서 여기로 넣어줌 (생성자 주입)
    @Autowired
    public ApiKeyResolver(ApiKeyRepository apiKeyRepository, ApiKeyCache cache) {
        this.apiKeyRepository = apiKeyRepository;
        this.cache = cache;
    }

    // 캐시를 따로 안 줄 때 (단위 테스트용): 기본 TTL 60초짜리 캐시
    public ApiKeyResolver(ApiKeyRepository apiKeyRepository) {
        this(apiKeyRepository, new ApiKeyCache(Duration.ofSeconds(60)));
    }

    public ResolvedKey resolve(String rawHeaderValue){
        // 1. 헤더가 없거나 공백뿐이면 DB 까지 갈 필요 없이 바로 실패
        if (rawHeaderValue == null || rawHeaderValue.isBlank()) {
            throw new InvalidApiKeyException("invalid api key");
        }

        // 2. 받은 키를 DB에 저장된 방식과 똑같이 해시함 (DB에는 원문이 없고 해시만 있음)
        String keyHash = ApiKeyHasher.sha256Hex(rawHeaderValue);

        // 3. 캐시에 살아 있으면 DB 를 안 봄 (폐기되면 evict 로 바로 지워진다)
        ResolvedKey cached = cache.get(keyHash);
        if (cached != null) {
            return cached;
        }

        // 4. 해시가 같고 폐기되지 않은(revoked_at 이 비어 있는) 키만 찾음 (api_key 는 RLS 없음)
        dbLookups.incrementAndGet();
        ApiKeyEntity key = apiKeyRepository.findByKeyHashAndRevokedAtIsNull(keyHash)
                .orElseThrow(() -> {
                    // 원문은 절대 안 찍고 해시 앞 8글자만 (어느 키인지 구분용)
                    log.warn("api key rejected hash={}" , keyHash.substring(0, 8));
                    // 없는 키와 폐기된 키를 구분하지 않으려고 메시지를 항상 똑같이 씀. 캐시에는 넣지 않음 (음성 캐싱 금지)
                    return new InvalidApiKeyException("invalid api key");
                });

        // 5. 정지된 팀(SUSPENDED)의 키도 거부 — tenant.status(V3)가 생기면 13단계에서 이 자리에 조건 추가

        // 6. 통과: 필요한 값만 꺼내서 결과 상자에 담아 캐시에 넣고 돌려줌
        ResolvedKey resolved = new ResolvedKey(key.getTenantId(), key.getEnv(), key.getId());
        cache.put(keyHash, resolved);
        return resolved;
    }

    // 키 폐기 커밋 뒤 호출 (ApiKeyService.revoke). 그 키로 오는 다음 요청은 DB 를 다시 봐서 401
    public void evict(String keyHash) {
        cache.evict(keyHash);
    }

    // 지금까지 DB 를 조회한 횟수 (캐시 확인용)
    long dbLookupCount() {
        return dbLookups.get();
    }
}
