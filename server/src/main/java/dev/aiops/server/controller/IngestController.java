package dev.aiops.server.controller;

import dev.aiops.protocol.EventBatch;
import dev.aiops.protocol.Heartbeat;      
import dev.aiops.protocol.HelloRequest;   
import dev.aiops.protocol.ProtocolConstants;
import dev.aiops.server.security.ApiKeyResolver;
import dev.aiops.server.security.ResolvedKey;
import dev.aiops.server.service.IngestService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

// Starter 가 보내는 에러 묶음(AI-31)·hello·heartbeat(AI-33)를 받는 문
@RestController
public class IngestController {

    private final ApiKeyResolver apiKeyResolver;
    private final IngestService ingestService;

    public IngestController(ApiKeyResolver apiKeyResolver, IngestService ingestService) {
        this.apiKeyResolver = apiKeyResolver;
        this.ingestService = ingestService;
    }

    @PostMapping(ProtocolConstants.PATH_EVENTS)   // POST /v1/events
    public ResponseEntity<Void> postEvents(
            // required=false: 헤더가 없어도 스프링이 400 을 내지 않고 Resolver 가 401 을 내게 함
            @RequestHeader(value = ProtocolConstants.HEADER_API_KEY, required = false) String apiKey,
            @RequestBody EventBatch batch) {

        // 1. 키 확인 (실패하면 InvalidApiKeyException → 401)
        ResolvedKey key = apiKeyResolver.resolve(apiKey);

        // 2. 검증 (100개 초과면 TooManyEventsException → 400)
        ingestService.validate(batch);

        // 3. 저장 (키로 정한 팀·env 를 씀. 본문의 env 는 믿지 않음)
        ingestService.store(key.tenantId(), key.env(), batch);

        // 4. 202 Accepted, 본문 없음
        return ResponseEntity.accepted().build();
    }

    // AI-33 추가: 앱이 켜진 뒤 1번 오는 환경 정보
    @PostMapping(ProtocolConstants.PATH_HELLO)   // POST /v1/hello
    public ResponseEntity<Void> postHello(
            @RequestHeader(value = ProtocolConstants.HEADER_API_KEY, required = false) String apiKey,
            @RequestBody HelloRequest hello) {
        ResolvedKey key = apiKeyResolver.resolve(apiKey);   // 키 실패 → 401
        ingestService.storeHello(key.tenantId(), key.env(), hello);
        return ResponseEntity.accepted().build();
    }

    // AI-33 추가: 60초마다 오는 "살아 있음" 신호
    @PostMapping(ProtocolConstants.PATH_HEARTBEAT)   // POST /v1/heartbeat
    public ResponseEntity<Void> postHeartbeat(
            @RequestHeader(value = ProtocolConstants.HEADER_API_KEY, required = false) String apiKey,
            @RequestBody Heartbeat heartbeat) {
        ResolvedKey key = apiKeyResolver.resolve(apiKey);
        ingestService.recordHeartbeat(key.tenantId(), key.env(), heartbeat);
        return ResponseEntity.accepted().build();
    }
}