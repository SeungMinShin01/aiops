package dev.aiops.server.security;

/** OAuth state가 위조·만료·형식 오류일 때. 컨트롤러는 400으로 바꾼다 (AI-93) */
public class InvalidOAuthStateException extends RuntimeException {
    public InvalidOAuthStateException(String reason) {
        super("invalid oauth state: " + reason);
    }
}
