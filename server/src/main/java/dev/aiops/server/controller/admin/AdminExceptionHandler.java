package dev.aiops.server.controller.admin;

import dev.aiops.server.service.AdminApiException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// AI-82 : 관리자 API(controller/admin) 에러 응답 형식을 { "error": 코드, "message": 설명 } 로 통일. 13단계 나머지 작업도 같이 씀
// basePackages 로 관리자 컨트롤러에만 적용 (다른 API 의 에러 형식은 건드리지 않음)
@RestControllerAdvice(basePackages = "dev.aiops.server.controller.admin")
public class AdminExceptionHandler {

    // AI-82 : 서비스가 던진 400·404·409
    @ExceptionHandler(AdminApiException.class)
    ResponseEntity<Map<String, String>> handleAdminApi(AdminApiException e) {
        return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getCode(), "message", e.getMessage()));
    }

    // AI-82 : 본문이 없거나 JSON·날짜 형식이 틀림 → 400
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "INVALID_REQUEST", "message", "요청 본문을 읽을 수 없습니다"));
    }
}