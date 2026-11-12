package dev.aiops.server.dto;

/** 관리자 키 발급 응답 (AI-88). key가 원문 — 이 응답 1회만. 관리자가 디스코드 DM으로 전달한다 (규칙 40) */
public record KeyIssued(long keyId, String env, String key) {

    @Override
    public String toString() {   // 실수로 로그에 찍혀도 원문이 나가지 않게
        return "KeyIssued[keyId=" + keyId + ", env=" + env + "]";
    }
}
