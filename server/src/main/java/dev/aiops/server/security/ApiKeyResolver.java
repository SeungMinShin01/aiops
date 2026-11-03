package dev.aiops.server.security;

import dev.aiops.server.model.entity.ApiKeyEntity;
import dev.aiops.server.model.repository.ApiKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
// 요청 헤더의 키 원문 → "어느 팀, 어느 env" 로 바꾸는 곳. 인증 판단은 이 클래스 한 곳에만 둠
@Component // 스프링이 이 클래스의 객체를 만들어서 관리 (AI-31 컨트롤러가 가져다 씀)
public class ApiKeyResolver {
    // 이 클래스에서 로그를 찍는 도구
    private static final Logger log = LoggerFactory.getLogger(ApiKeyResolver.class);
    // DB에서 키를 찾아주는 도구. final 이라 한 번 넣으면 바뀌지 않음
    private final ApiKeyRepository apiKeyRepository;
    // 스프링이 ApiKeyRepository 를 만들어서 여기로 넣어줌 (생성자 주입)
    public ApiKeyResolver(ApiKeyRepository apiKeyRepository){
        this.apiKeyRepository = apiKeyRepository;
    }

    public ResolvedKey resolve(String rawHeaderValue){
        // 1. 헤더가 없거나 공백뿐이면 DB 까지 갈 필요 없이 바로 실패
        if (rawHeaderValue == null || rawHeaderValue.isBlank()) {
            throw new InvalidApiKeyException("invalid api key");
        }

        // 2. 받은 키를 DB에 저장된 방식과 똑같이 해시함 (DB에는 원문이 없고 해시만 있음)
        String keyHash = ApiKeyHasher.sha256Hex(rawHeaderValue);
        // 3. 해시가 같고 폐기되지 않은(revoked_at 이 비어 있는) 키만 찾음
        ApiKeyEntity key = apiKeyRepository.findByKeyHashAndRevokedAtIsNull(keyHash)
                .orElseThrow(() -> {
                    // 원문은 절대 안 찍고 해시 앞 8글자만 (어느 키인지 구분용)
                    log.warn("api key rejected hash={}" , keyHash.substring(0, 8));
                    // 없는 키와 폐기된 키를 구분하지 않으려고 메시지를 항상 똑같이 씀
                    return new InvalidApiKeyException("invalid api key");
                });
        // 4. 통과: 필요한 값만 꺼내서 결과 상자에 담아 돌려줌
        return new ResolvedKey(key.getTenantId(), key.getEnv(), key.getId());
    }
}
