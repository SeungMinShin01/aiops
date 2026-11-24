package dev.aiops.server.client;

/**
 * 디스코드가 만들어 준 웹훅. url은 "아는 사람은 누구나 메시지를 보낼 수 있는" 비밀값이라
 * 반환값으로만 넘기고 toString에는 id만 찍는다 (규칙 10·40).
 */
public record WebhookInfo(String id, String url) {

    @Override
    public String toString() {
        return "WebhookInfo[id=" + id + "]";
    }
}
