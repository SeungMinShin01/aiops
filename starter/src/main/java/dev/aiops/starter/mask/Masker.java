package dev.aiops.starter.mask;

import dev.aiops.protocol.ErrorEvent;

/**
 * 부품 ⑤. 에러의 민감정보를 *** 로 가리고, 메시지를 최대 길이로 자른다.
 * 어펜더가 팀 앱 스레드에서 부르므로 빨라야 하고, 예외를 던지지 않는다.
 * 실패하면 메시지를 비운다 (유출보다 누락).
 */
public interface Masker {

    String MASK = "***";

    /**
     * message와 causes[].message에 maskText를 적용한 새 이벤트.
     * 스택 프레임·예외 타입·스레드·시각·단계는 그대로 (지문 계산에 쓰인다). null이면 null
     */
    ErrorEvent mask(ErrorEvent event);

    /**
     * 문자열 하나를 가린다. null이면 null, 결과는 최대 ProtocolConstants.MAX_MESSAGE_LENGTH(2000)자,
     * 실패하면 ""
     */
    String maskText(String text);
}