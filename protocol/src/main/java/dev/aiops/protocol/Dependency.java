package dev.aiops.protocol;

/** 클래스패스에서 읽은 라이브러리 하나. version은 모르면 null */
public record Dependency(
                String group,
                String artifact,
                String version) {
}