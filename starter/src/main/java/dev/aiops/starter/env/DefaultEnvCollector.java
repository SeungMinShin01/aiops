package dev.aiops.starter.env;

import dev.aiops.protocol.AppInfo;
import dev.aiops.protocol.HelloRequest;
import org.springframework.boot.SpringBootVersion;
import org.springframework.core.env.Environment;

/**
 * EnvCollector 구현 (AI-47). env와 AppInfo(앱 이름·커밋·호스트 해시)는 Starter 시작 때 정해진 것을
 * 받는다.
 * 각 칸은 따로 실패한다: 한 칸을 못 읽어도 나머지는 보낸다.
 */
public final class DefaultEnvCollector implements EnvCollector {

    private final String env;
    private final AppInfo appInfo;

    public DefaultEnvCollector(String env, AppInfo appInfo) {
        this.env = env;
        this.appInfo = appInfo;
    }

    @Override
    public HelloRequest collect(Environment environment) {
        return new HelloRequest(env, appInfo, javaVersion(), springBootVersion(),
                DependencyLister.list(), ConfigKeyLister.list(environment));
    }

    private static String javaVersion() {
        try {
            return Runtime.version().toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String springBootVersion() {
        try {
            return SpringBootVersion.getVersion();
        } catch (Throwable t) {
            return null;
        }
    }
}