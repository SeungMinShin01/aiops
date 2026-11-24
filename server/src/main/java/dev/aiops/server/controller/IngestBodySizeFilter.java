package dev.aiops.server.controller;

import dev.aiops.protocol.ProtocolConstants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

// 요청 본문이 너무 크면 컨트롤러에 닿기 전에 413 으로 돌려보내는 검문소
@Component
public class IngestBodySizeFilter extends OncePerRequestFilter {

    // application.yml 의 한도 (없으면 1MB)
    private final long maxBytes;

    public IngestBodySizeFilter(@Value("${aiops.ingest.max-body-bytes:1048576}") long maxBytes) {
        this.maxBytes = maxBytes;
    }

    // /v1/events 요청만 검사하고 나머지 경로는 그냥 통과
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !ProtocolConstants.PATH_EVENTS.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // 헤더에 적힌 본문 크기가 한도를 넘으면 본문을 읽지 않고 413
        if (request.getContentLengthLong() > maxBytes) {
            response.setStatus(413);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"payload_too_large\"}");
            return;
        }
        chain.doFilter(request, response);   // 통과
    }
}