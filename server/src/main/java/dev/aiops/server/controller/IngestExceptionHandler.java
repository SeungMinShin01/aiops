package dev.aiops.server.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import dev.aiops.server.security.InvalidApiKeyException;
// 모든 컨트롤러에서 터진 예외를 여기서 한 번에 받아서 JSON 으로 응답함
@RestControllerAdvice 
public class IngestExceptionHandler {
    // InvalidApiKeyException 이 던져지면 이 메서드가 대신 응답을 만듦
    @ExceptionHandler(InvalidApiKeyException.class)
    public ResponseEntity<Map<String, String>> handleInvalidApiKey(InvalidApiKeyException e){
        // 상태코드 401, 본문 {"error":"invalid_api_key"}
        // e.getMessage() 는 응답에 넣지 않음 (키가 없는지 폐기됐는지 밖에 알려주지 않으려고)
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error","invalid_api_key"));
    }
}
