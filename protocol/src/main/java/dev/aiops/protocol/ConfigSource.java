package dev.aiops.protocol;

/** 설정 키가 어디서 왔는지. 값은 어디에도 싣지 않는다 (규칙 6) */
public enum ConfigSource {
    YML, PROPERTIES, ENV, SYSTEM_PROPERTY, OTHER
}