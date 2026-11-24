package dev.aiops.server.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;

/**
 * 디스코드 연결용 OAuth state — 서버가 서명한 "누가, 어느 팀으로" (AI-93).
 * <p>
 * 형식: base64url(payload) + "." + base64url(HMAC-SHA256(secret, payload)), payload = "userId:teamId:만료epoch초".
 * 콜백에서 서명·만료를 검증하므로 teamId를 바꿔치기할 수 없다. 만료 10분.
 * secret은 환경변수 AIOPS_STATE_SECRET (코드·로그 금지, 규칙 10). 비어 있으면 만들지도 검증하지도 않는다.
 */
@Component
public class OAuthState {

    static final Duration TTL = Duration.ofMinutes(10);
    private static final String HMAC = "HmacSHA256";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    /** 검증된 state 내용 */
    public record Payload(Long userId, Long teamId) {
    }

    private final byte[] secret;
    private final Clock clock;

    @Autowired
    public OAuthState(@Value("${aiops.state-secret:}") String secret) {
        this(secret, Clock.systemUTC());
    }

    OAuthState(String secret, Clock clock) {
        this.secret = (secret == null) ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    public String create(Long userId, Long teamId) {
        requireSecret();
        long expires = clock.instant().plus(TTL).getEpochSecond();
        String payload = userId + ":" + teamId + ":" + expires;
        return B64.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + B64.encodeToString(sign(payload));
    }

    /** 서명·만료·형식을 검증하고 내용을 돌려준다. 하나라도 틀리면 InvalidOAuthStateException */
    public Payload verify(String state) {
        requireSecret();
        if (state == null || state.isBlank()) {
            throw new InvalidOAuthStateException("empty");
        }
        int dot = state.indexOf('.');
        if (dot <= 0 || dot == state.length() - 1) {
            throw new InvalidOAuthStateException("format");
        }
        String payload;
        byte[] signature;
        try {
            payload = new String(B64D.decode(state.substring(0, dot)), StandardCharsets.UTF_8);
            signature = B64D.decode(state.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            throw new InvalidOAuthStateException("encoding");
        }
        // 시간차 공격을 막는 비교 (MessageDigest.isEqual)
        if (!MessageDigest.isEqual(sign(payload), signature)) {
            throw new InvalidOAuthStateException("signature");
        }
        String[] parts = payload.split(":");
        if (parts.length != 3) {
            throw new InvalidOAuthStateException("format");
        }
        try {
            long expires = Long.parseLong(parts[2]);
            if (clock.instant().getEpochSecond() >= expires) {
                throw new InvalidOAuthStateException("expired");
            }
            return new Payload(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
        } catch (NumberFormatException e) {
            throw new InvalidOAuthStateException("format");
        }
    }

    private void requireSecret() {
        if (secret.length == 0) {
            throw new IllegalStateException("AIOPS_STATE_SECRET이 설정되지 않았다");
        }
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret, HMAC));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 계산 실패", e);
        }
    }
}
