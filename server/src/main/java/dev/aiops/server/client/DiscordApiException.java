package dev.aiops.server.client;

/**
 * 디스코드 REST 호출 실패. 메시지에는 HTTP 상태와 어떤 호출이었는지만 담는다.
 * 요청·응답 본문은 넣지 않는다 (웹훅 url·토큰이 섞일 수 있다, 규칙 10·40).
 * 같은 이유로 원래 예외를 cause로 붙이지 않는다 (RestClient 예외 메시지에 응답 본문이 들어 있다).
 */
public class DiscordApiException extends RuntimeException {

    /** 연결 실패·타임아웃처럼 HTTP 응답이 없을 때의 status */
    public static final int NO_RESPONSE = 0;

    private final int status;
    private final String operation;

    public DiscordApiException(int status, String operation) {
        super("디스코드 API 실패: " + operation + " (status " + (status == NO_RESPONSE ? "응답 없음" : status) + ")");
        this.status = status;
        this.operation = operation;
    }

    public int getStatus() {
        return status;
    }

    public String getOperation() {
        return operation;
    }
}
