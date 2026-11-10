package dev.aiops.starter.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HostHashTest {

    @Test
    void 해시는_16진수_12자이고_원문을_담지_않는다() {
        String h = HostHash.hash("my-laptop");
        assertEquals(12, h.length());
        assertTrue(h.matches("[0-9a-f]{12}"));
        assertFalse(h.contains("my-laptop"));
    }

    @Test
    void 같은_입력이면_같은_해시() {
        assertEquals(HostHash.hash("pc-1"), HostHash.hash("pc-1"));
        assertNotEquals(HostHash.hash("pc-1"), HostHash.hash("pc-2"));
    }

    @Test
    void 실제_호스트에서도_null이_아니다() {
        String h = HostHash.get();
        assertNotNull(h); // 서버 host_hash가 NOT NULL
        assertEquals(12, h.length());
    }
}