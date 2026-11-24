package dev.aiops.starter.transport;

import dev.aiops.protocol.EventBatch;
import dev.aiops.protocol.Heartbeat;
import dev.aiops.protocol.HelloRequest;

/**
 * 서버로 HTTP POST 한 번만 담당 (부품 ⑧). 언제 보낼지는 EventSender 몫.
 * 서버가 죽어 있어도 최대 3초 안에 false. 예외를 던지지 않는다 (규칙 4).
 * 구현은 HttpTransport + JsonWriter (AI-37, Jackson 미사용 D-085). 메서드 이름은 바꾸지 않는다
 * (규칙 2).
 */
public interface Transport {

    /** POST /v1/events. 2xx면 true */
    boolean send(EventBatch batch);

    /** POST /v1/hello. 2xx면 true */
    boolean hello(HelloRequest hello);

    /** POST /v1/heartbeat. 2xx면 true (D-086) */
    boolean heartbeat(Heartbeat heartbeat);
}