package dev.aiops.server.security;

import java.io.Serializable;

/**
 * 세션에 들어가는 로그인 사용자 (AI-78 명세). GitHub 속성이 아니라 우리 app_user id를 쓰게 한다.
 * 필드 이름·순서를 바꾸지 않는다 (규칙 2). 권한 E2E 테스트(AI-76)가 이 record로 세션을 만든다.
 */
public record AppUserPrincipal(long appUserId, long githubId, String login) implements Serializable {
}