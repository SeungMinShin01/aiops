package dev.aiops.server.service;

// 한 묶음의 이벤트가 최대 개수(100)를 넘었을 때 던지는 예외. IngestExceptionHandler 가 400 으로 바꿈
public class TooManyEventsException extends RuntimeException {

    public TooManyEventsException(String message) {
        super(message);
    }
}