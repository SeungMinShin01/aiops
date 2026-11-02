package dev.aiops.starter.transport;

import dev.aiops.protocol.HelloRequest;

import java.time.Duration;

/**
 * Starter 전용 데몬 스레드(aiops-sender)가 버퍼를 비워 묶음으로 보낸다 (부품 ⑦).
 * 5초 또는 100개 중 먼저, 실패 시 1회 재시도 후 포기, 60초마다 하트비트 (D-086).
 * 구현은 BatchingEventSender (AI-38). 메서드 이름은 바꾸지 않는다 (규칙 2) — 5단계 생명주기가 부른다.
 */
public interface EventSender {

    /** 전송 스레드 시작. 이미 시작했으면 아무것도 하지 않는다 */
    void start();

    /** 앱 준비 완료 후 hello 1건을 맡긴다. 전송 스레드가 다음 바퀴에 1회 보낸다 (실패하면 포기) */
    void submitHello(HelloRequest hello);

    /** 부른 스레드에서 직접 버퍼를 비워 보낸다. 최대 maxWait까지만 (종료 훅·시작 실패용) */
    void flushNow(Duration maxWait);

    /** 스레드 정지. 최대 1초 기다린다 */
    void stop();
}