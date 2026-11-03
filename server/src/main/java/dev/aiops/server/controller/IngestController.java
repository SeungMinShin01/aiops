package dev.aiops.server.controller;

import dev.aiops.protocol.EventBatch;
import dev.aiops.protocol.ProtocolConstants;
import dev.aiops.server.security.ApiKeyResolver;
import dev.aiops.server.security.ResolvedKey;
import dev.aiops.server.service.IngestService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

// Starter 가 보내는 에러 묶음을 받는 문. hello·heartbeat 는 뒤 단위에서 이 클래스에 추가함
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

        // 3. 저장은 뒤 단계(store)에서 key 를 써서 추가함

        // 4. 202 Accepted, 본문 없음
        return ResponseEntity.accepted().build();
    }
}