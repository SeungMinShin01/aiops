package dev.aiops.server.controller;

import dev.aiops.server.security.InvalidApiKeyException;
import dev.aiops.server.service.TooManyEventsException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// 모든 컨트롤러에서 터진 예외를 여기서 한 번에 받아서 JSON 으로 응답함
@RestControllerAdvice
public class IngestExceptionHandler {

    // 키가 없거나 틀리거나 폐기됨 → 401. 예외 메시지는 응답에 넣지 않음 (구분해서 알려주지 않으려고)
    @ExceptionHandler(InvalidApiKeyException.class)
    public ResponseEntity<Map<String, String>> handleInvalidApiKey(InvalidApiKeyException e) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "invalid_api_key"));
    }

    // 한 묶음의 이벤트가 100개를 넘음 → 400
    @ExceptionHandler(TooManyEventsException.class)
    public ResponseEntity<Map<String, String>> handleTooManyEvents(TooManyEventsException e) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "too_many_events"));
    }
}