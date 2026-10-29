package dev.aiops.protocol;

import java.util.List;

/** POST /v1/hello 본문. 앱이 준비된 뒤 1회 전송하는 환경 정보 */
public record HelloRequest(
        String env,
        AppInfo app,
        String javaVersion,
        String springBootVersion,
        List<Dependency> dependencies,
        List<ConfigKey> configKeys) {
    public HelloRequest {
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        configKeys = configKeys == null ? List.of() : List.copyOf(configKeys);
    }
}