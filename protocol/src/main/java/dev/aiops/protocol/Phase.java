package main.java.dev.aiops.protocol;

/** 에러가 난 시점. STARTUP = 앱이 뜨다가 실패, RUNTIME = 뜬 뒤 요청 처리 중 */
public enum Phase {
    STARTUP, RUNTIME
}