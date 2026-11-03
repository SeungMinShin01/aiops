package dev.aiops.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class ApiKeyHasherTest {

    // "abc" 의 SHA-256 은 세계 공통 정답값이 있음 → 계산 방식이 맞는지 확인
    @Test
    void abc는_알려진_SHA256_값이_나온다() {
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                ApiKeyHasher.sha256Hex("abc"));
    }

    // R__seed_local.sql 에 들어있는 해시와 같아야, DB의 시드 키로 실제 인증이 됨
    @Test
    void 시드_키는_DB에_들어있는_해시와_같다() {
        assertEquals(
                "f0e8ca65e485ef8f10c8c35c7f0cb6417582105c7237e41f8cc692ac31f1e1f6",
                ApiKeyHasher.sha256Hex("aiops_l_local-dev-key-0001"));
    }

    // 키가 다르면 해시도 달라야 함 (아무 키나 통과하는 버그 방지)
    @Test
    void 다른_입력은_다른_해시가_나온다() {
        assertNotEquals(ApiKeyHasher.sha256Hex("key-a"), ApiKeyHasher.sha256Hex("key-b"));
    }
}