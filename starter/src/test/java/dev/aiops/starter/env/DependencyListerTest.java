package dev.aiops.starter.env;

import dev.aiops.protocol.Dependency;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

// AI-47 : jar 파일 이름 → (이름, 버전)
class DependencyListerTest {

    @Test
    void 이름과_버전을_나눈다() {
        Dependency d = DependencyLister.parse("spring-web-6.1.5.jar");

        assertEquals("spring-web", d.artifact());
        assertEquals("6.1.5", d.version());
        assertNull(d.group());
    }

    @Test
    void 이름에_숫자가_섞여도_마지막_버전만_뗀다() {
        assertEquals("log4j-to-slf4j", DependencyLister.parse("log4j-to-slf4j-2.24.3.jar").artifact());
        assertEquals("commons-lang3", DependencyLister.parse("commons-lang3-3.17.0.jar").artifact());
        assertEquals("2.3.232", DependencyLister.parse("h2-2.3.232.jar").version());
    }

    @Test
    void 모양이_안_맞으면_건너뛴다() {
        assertNull(DependencyLister.parse("protocol.jar"));
        assertNull(DependencyLister.parse("classes"));
        assertNull(DependencyLister.parse(""));
        assertNull(DependencyLister.parse(null));
    }

    @Test
    void 경로에서_파일_이름만_남긴다() {
        assertEquals("spring-web-6.1.5.jar",
                DependencyLister.fileNameOf("C:\\Users\\someone\\.gradle\\caches\\spring-web-6.1.5.jar"));
        assertEquals("spring-web-6.1.5.jar",
                DependencyLister.fileNameOf("jar:nested:/app/app.jar/!BOOT-INF/lib/spring-web-6.1.5.jar!/"));
    }

    @Test
    void 최대_300개() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            names.add("lib" + (char) ('a' + i % 26) + "x" + i + "y-1.0." + i + ".jar");
        }

        assertEquals(DependencyLister.MAX, DependencyLister.fromFileNames(names).size());
    }

    @Test
    void 실제_클래스패스에서도_예외_없이_목록이_나온다() {
        List<Dependency> all = DependencyLister.list();

        assertFalse(all.isEmpty(), "테스트 클래스패스에는 버전이 붙은 jar가 있다");
        for (Dependency d : all) {
            assertFalse(d.artifact().contains("/"), "경로가 들어가면 안 된다");
            assertFalse(d.artifact().contains("\\"), "경로가 들어가면 안 된다");
        }
    }
}