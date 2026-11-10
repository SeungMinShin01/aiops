package dev.aiops.starter.mask;

/**
 * 부품 ⑤. 에러 메시지의 민감정보를 *** 로 가리고, 최대 길이로 자른다.
 * 어펜더가 팀 앱 스레드에서 부르므로 빨라야 하고, 예외를 던지지 않는다.
 * 실패하면 빈 문자열을 돌려준다 (유출보다 누락).
 * 어펜더(AI-42)는 ErrorEvent.message와 causes의 message 각각에 적용한다.
 */
public interface Masker {

    String MASK = "***";

    /** null이면 null. 결과 길이는 최대 ProtocolConstants.MAX_MESSAGE_LENGTH(2000) */
    String mask(String raw);
}