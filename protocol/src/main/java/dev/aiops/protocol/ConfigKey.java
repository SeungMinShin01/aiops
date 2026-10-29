package main.java.dev.aiops.protocol;

/** 설정 키 이름과 출처만. 값 필드는 없고, 앞으로도 추가하지 않는다 (규칙 6) */
public record ConfigKey(
        String key,
        ConfigSource source) {
}