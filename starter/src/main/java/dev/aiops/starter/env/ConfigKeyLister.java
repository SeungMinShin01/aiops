package dev.aiops.starter.env;

import dev.aiops.protocol.ConfigKey;
import dev.aiops.protocol.ConfigSource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 설정 키의 "이름"과 "어디서 왔는지"만 모은다 (AI-47).
 * 값은 읽지 않는다: 이 클래스에는 getProperty 호출이 없다. 이름 목록(getPropertyNames)만 쓴다 (규칙 6).
 * 가려서 보내는 것도 하지 않는다 — 아예 읽지 않는다.
 */
final class ConfigKeyLister {

    static final int MAX = 500;
    static final int MAX_KEY_LENGTH = 200;

    private ConfigKeyLister() {
    }

    static List<ConfigKey> list(Environment environment) {
        try {
            if (!(environment instanceof ConfigurableEnvironment configurable)) {
                return List.of();
            }
            List<ConfigKey> fromFiles = new ArrayList<>(); // 앱 설정 파일 (먼저 싣는다)
            List<ConfigKey> others = new ArrayList<>(); // 환경변수·시스템 속성·그 밖
            for (PropertySource<?> source : configurable.getPropertySources()) {
                if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                    continue;
                }
                ConfigSource kind = kindOf(source.getName());
                boolean file = kind == ConfigSource.YML || kind == ConfigSource.PROPERTIES;
                for (String name : enumerable.getPropertyNames()) { // 이름만
                    if (name == null || name.isEmpty() || name.length() > MAX_KEY_LENGTH) {
                        continue;
                    }
                    (file ? fromFiles : others).add(new ConfigKey(name, kind));
                }
            }
            List<ConfigKey> result = new ArrayList<>();
            for (ConfigKey key : fromFiles) {
                if (result.size() >= MAX) {
                    return result;
                }
                result.add(key);
            }
            for (ConfigKey key : others) {
                if (result.size() >= MAX) {
                    return result;
                }
                result.add(key);
            }
            return result;
        } catch (Throwable t) {
            return List.of();
        }
    }

    /** PropertySource 이름으로 출처를 정한다. 모르면 OTHER */
    static ConfigSource kindOf(String sourceName) {
        if (sourceName == null) {
            return ConfigSource.OTHER;
        }
        String name = sourceName.toLowerCase(Locale.ROOT);
        if (name.contains(".yml") || name.contains(".yaml")) {
            return ConfigSource.YML;
        }
        if (name.contains(".properties")) {
            return ConfigSource.PROPERTIES;
        }
        if (name.equals("systemenvironment")) {
            return ConfigSource.ENV;
        }
        if (name.equals("systemproperties")) {
            return ConfigSource.SYSTEM_PROPERTY;
        }
        return ConfigSource.OTHER;
    }
}