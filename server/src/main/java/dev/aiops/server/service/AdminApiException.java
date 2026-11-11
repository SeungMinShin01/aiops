package dev.aiops.server.service;

import org.springframework.http.HttpStatus;

// AI-82 : 관리자 API 공통 예외. 13단계 나머지 작업(팀 생성·초대·정지)도 이 예외를 던진다
// controller/admin/AdminExceptionHandler 가 { "error": 코드, "message": 설명 } 로 바꿔 응답함
public class AdminApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public AdminApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}