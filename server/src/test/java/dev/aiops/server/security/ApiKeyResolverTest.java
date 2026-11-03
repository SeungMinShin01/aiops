package dev.aiops.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.aiops.server.model.entity.ApiKeyEntity;
import dev.aiops.server.model.repository.ApiKeyRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ApiKeyResolverTest {

    // DB 대신 쓰는 가짜 repository. 내가 정한 대로만 대답함
    private final ApiKeyRepository repository = mock(ApiKeyRepository.class);
    // 진짜 Resolver 에 가짜 repository 를 끼워서 만듦
    private final ApiKeyResolver resolver = new ApiKeyResolver(repository);

    // 정상: 맞는 키면 DB 에서 찾은 값으로 팀·env·keyId 를 돌려줌
    @Test
    void 정상_키는_tenantId_env_keyId를_돌려준다() {
        String raw = "my-test-key";
        ApiKeyEntity entity = mock(ApiKeyEntity.class);   // 가짜 DB 한 줄
        when(entity.getTenantId()).thenReturn(1L);        // "팀 id 물으면 1"
        when(entity.getEnv()).thenReturn("local");        // "env 물으면 local"
        when(entity.getId()).thenReturn(10L);             // "키 id 물으면 10"
        // Resolver 가 계산할 해시를 똑같이 계산해서, 그 해시로 찾으면 위 줄을 돌려주게 함
        when(repository.findByKeyHashAndRevokedAtIsNull(ApiKeyHasher.sha256Hex(raw)))
                .thenReturn(Optional.of(entity));

        ResolvedKey result = resolver.resolve(raw);

        assertEquals(new ResolvedKey(1L, "local", 10L), result);
    }

    // 없는 키: DB 가 못 찾았다고 대답하면 예외
    @Test
    void DB에_없는_키는_예외가_난다() {
        when(repository.findByKeyHashAndRevokedAtIsNull(ApiKeyHasher.sha256Hex("unknown")))
                .thenReturn(Optional.empty());

        assertThrows(InvalidApiKeyException.class, () -> resolver.resolve("unknown"));
    }

    // 폐기된 키: repository 는 revoked_at 이 있는 키를 아예 돌려주지 않음(메서드 이름의 IsNull).
    // 그래서 DB 입장에서는 없는 키와 똑같이 empty. 그래도 티켓의 케이스라서 따로 둠
    @Test
    void 폐기된_키는_예외가_난다() {
        when(repository.findByKeyHashAndRevokedAtIsNull(ApiKeyHasher.sha256Hex("revoked-key")))
                .thenReturn(Optional.empty());

        assertThrows(InvalidApiKeyException.class, () -> resolver.resolve("revoked-key"));
    }

    // 없는 키와 폐기 키를 구분해서 알려주지 않는다 → 예외 메시지가 같아야 함
    @Test
    void 없는_키와_폐기_키의_예외_메시지가_같다() {
        when(repository.findByKeyHashAndRevokedAtIsNull(ApiKeyHasher.sha256Hex("unknown")))
                .thenReturn(Optional.empty());
        when(repository.findByKeyHashAndRevokedAtIsNull(ApiKeyHasher.sha256Hex("revoked-key")))
                .thenReturn(Optional.empty());

        InvalidApiKeyException a = assertThrows(InvalidApiKeyException.class, () -> resolver.resolve("unknown"));
        InvalidApiKeyException b = assertThrows(InvalidApiKeyException.class, () -> resolver.resolve("revoked-key"));

        assertEquals(a.getMessage(), b.getMessage());
    }

    // 빈 헤더: DB 까지 가지 않고 바로 실패해야 함
    @Test
    void 키가_null이거나_비어있으면_DB를_보지_않고_예외가_난다() {
        assertThrows(InvalidApiKeyException.class, () -> resolver.resolve(null));
        assertThrows(InvalidApiKeyException.class, () -> resolver.resolve(""));
        assertThrows(InvalidApiKeyException.class, () -> resolver.resolve("   "));
        verifyNoInteractions(repository);   // 가짜 DB 를 한 번도 안 건드렸는지 확인
    }
}