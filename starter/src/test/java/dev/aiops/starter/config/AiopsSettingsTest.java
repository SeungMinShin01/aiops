package dev.aiops.starter.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class AiopsSettingsTest {

    private static final String KEY = "aiops_l_local-dev-key-0001";

    private static MockEnvironment withKey() {
        return new MockEnvironment().withProperty("aiops.api-key", KEY);
    }

    @Test
    void 키가_없으면_null() {
        assertNull(AiopsSettings.fromOrNull(new MockEnvironment()));
    }

    @Test
    void 키가_빈_문자열이면_null() {
        assertNull(AiopsSettings.fromOrNull(new MockEnvironment().withProperty("aiops.api-key", "   ")));
    }

    @Test
    void enabled가_false면_키가_있어도_null() {
        assertNull(AiopsSettings.fromOrNull(withKey().withProperty("aiops.enabled", "false")));
    }

    @Test
    void 키만_있으면_나머지는_기본값() {
        AiopsSettings s = AiopsSettings.fromOrNull(withKey());

        assertNotNull(s);
        assertEquals(KEY, s.apiKey());
        assertEquals("local", s.env());
        assertEquals(Defaults.ENDPOINT, s.endpoint());
        assertTrue(s.enabled());
        assertEquals(1_000, s.bufferCapacity());
        assertEquals(Duration.ofSeconds(5), s.flushInterval());
        assertEquals("unknown", s.appName());
    }

    @Test
    void 설정한_값을_그대로_읽는다() {
        AiopsSettings s = AiopsSettings.fromOrNull(withKey()
                .withProperty("aiops.env", "PROD")
                .withProperty("aiops.endpoint", "http://localhost:8080/")
                .withProperty("aiops.buffer-capacity", "500")
                .withProperty("aiops.flush-interval", "10s")
                .withProperty("spring.application.name", "shop"));

        assertEquals("prod", s.env());
        assertEquals("http://localhost:8080", s.endpoint()); // 끝 슬래시 제거
        assertEquals(500, s.bufferCapacity());
        assertEquals(Duration.ofSeconds(10), s.flushInterval());
        assertEquals("shop", s.appName());
    }

    @Test
    void env가_local_prod가_아니면_local() {
        assertEquals("local", AiopsSettings.fromOrNull(withKey().withProperty("aiops.env", "dev")).env());
    }

    @Test
    void 버퍼_크기가_범위_밖이면_1000() {
        assertEquals(1_000,
                AiopsSettings.fromOrNull(withKey().withProperty("aiops.buffer-capacity", "10")).bufferCapacity());
        assertEquals(1_000,
                AiopsSettings.fromOrNull(withKey().withProperty("aiops.buffer-capacity", "999999")).bufferCapacity());
        assertEquals(1_000,
                AiopsSettings.fromOrNull(withKey().withProperty("aiops.buffer-capacity", "abc")).bufferCapacity());
    }

    @Test
    void 전송_간격은_여러_표기를_받고_범위_밖이면_5초() {
        assertEquals(Duration.ofSeconds(3),
                AiopsSettings.fromOrNull(withKey().withProperty("aiops.flush-interval", "3")).flushInterval());
        assertEquals(Duration.ofMillis(1500),
                AiopsSettings.fromOrNull(withKey().withProperty("aiops.flush-interval", "1500ms")).flushInterval());
        assertEquals(Duration.ofSeconds(7),
                AiopsSettings.fromOrNull(withKey().withProperty("aiops.flush-interval", "PT7S")).flushInterval());
        assertEquals(Duration.ofSeconds(5),
                AiopsSettings.fromOrNull(withKey().withProperty("aiops.flush-interval", "0s")).flushInterval());
        assertEquals(Duration.ofSeconds(5),
                AiopsSettings.fromOrNull(withKey().withProperty("aiops.flush-interval", "120s")).flushInterval());
        assertEquals(Duration.ofSeconds(5),
                AiopsSettings.fromOrNull(withKey().withProperty("aiops.flush-interval", "soon")).flushInterval());
    }

    @Test
    void toString에_키_원문이_없다() {
        String text = AiopsSettings.fromOrNull(withKey()).toString();

        assertFalse(text.contains(KEY));
        assertTrue(text.contains("apiKey=***"));
    }
}