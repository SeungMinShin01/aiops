package dev.aiops.server.service;

/** 만료됐거나 사용 횟수를 다 쓴 초대 → 410 */
public class InvitationGoneException extends RuntimeException {
    public InvitationGoneException(String reason) {
        super("invitation gone: " + reason);
    }
}
