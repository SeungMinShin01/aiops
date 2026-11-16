package dev.aiops.starter.env;

import dev.aiops.protocol.AppInfo;
import dev.aiops.protocol.ConfigKey;
import dev.aiops.protocol.ConfigSource;
import dev.aiops.protocol.HelloRequest;
import dev.aiops.starter.runtime.HostHash;
import dev.aiops.starter.transport.JsonWriter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

// AI-47 : 환경 정보에 설정 "값"이 절대 들어가지 않는지가 핵심
class DefaultEnvCollectorTest {

    private static final AppInfo APP = new AppInfo("sample-app", null, null, HostHash.get());

    private static HelloRequest collect(MockEnvironment environment) {
        return new DefaultEnvCollector("local", APP).collect(environment);
    }

    @Test
    void configValuesAreNeverIncluded() {
        // 가짜 값 (진짜 비밀값 아님)
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.password", "secret123")
                .withProperty("my.token", "tok-9f3k2zz");

        String json = JsonWriter.write(collect(environment));

        assertFalse(json.contains("secret123"), "값이 실리면 안 된다");
        assertFalse(json.contains("tok-9f3k2zz"), "값이 실리면 안 된다");
        assertTrue(json.contains("spring.datasource.password"), "키 이름은 실린다");
        assertTrue(json.contains("my.token"));
    }

    @Test
    void 설정_키마다_출처가_있다() {
        HelloRequest hello = collect(new MockEnvironment().withProperty("a.b", "x"));

        assertFalse(hello.configKeys().isEmpty());
        for (ConfigKey key : hello.configKeys()) {
            assertNotNull(key.source());
        }
    }

    @Test
    void 출처_이름을_다섯_종류로_바꾼다() {
        assertEquals(ConfigSource.YML, ConfigKeyLister.kindOf(
                "Config resource 'class path resource [application.yml]' via location 'optional:classpath:/'"));
        assertEquals(ConfigSource.PROPERTIES, ConfigKeyLister.kindOf(
                "Config resource 'class path resource [application.properties]'"));
        assertEquals(ConfigSource.ENV, ConfigKeyLister.kindOf("systemEnvironment"));
        assertEquals(ConfigSource.SYSTEM_PROPERTY, ConfigKeyLister.kindOf("systemProperties"));
        assertEquals(ConfigSource.OTHER, ConfigKeyLister.kindOf("commandLineArgs"));
        assertEquals(ConfigSource.OTHER, ConfigKeyLister.kindOf(null));
    }

    @Test
    void 호스트는_해시_12자만_실린다() {
        HelloRequest hello = collect(new MockEnvironment());

        assertEquals(12, hello.app().hostHash().length());
    }

    @Test
    void 버전과_env가_실린다() {
        HelloRequest hello = collect(new MockEnvironment());

        assertEquals("local", hello.env());
        assertNotNull(hello.javaVersion());
        assertNotNull(hello.springBootVersion());
        assertFalse(hello.dependencies().isEmpty());
    }

    @Test
    void 환경이_null이어도_예외가_없다() {
        HelloRequest hello = assertDoesNotThrow(() -> new DefaultEnvCollector("local", APP).collect(null));

        assertTrue(hello.configKeys().isEmpty());
    }
}