package dev.aiops.server.service;

/** 초대 토큰에 해당하는 초대가 없음 → 404 */
public class InvitationNotFoundException extends RuntimeException {
    public InvitationNotFoundException() {
        super("invitation not found");
    }
}
