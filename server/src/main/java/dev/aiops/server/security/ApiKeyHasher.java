package dev.aiops.server.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;


// API 키 원문을 SHA-256 해시(64글자)로 변환
public final class ApiKeyHasher {
    
    private ApiKeyHasher() {

    }

    public static String sha256Hex(String raw) {
        try{
            
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // UTF-8 로 고정
            byte[] hashBytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            // 32바이트 → 소문자 16진수 64글자 (DB의 CHAR(64) 와 같은 형태)
            return HexFormat.of().formatHex(hashBytes);


        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다" , e);
        }
    }
}
