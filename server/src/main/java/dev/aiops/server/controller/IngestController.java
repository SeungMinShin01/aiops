package dev.aiops.server.controller;

import dev.aiops.protocol.EventBatch;
import dev.aiops.protocol.ProtocolConstants;
import dev.aiops.server.security.TenantFilter;
import dev.aiops.server.service.IngestService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// Starter 가 보내는 에러 묶음을 받는 문. hello·heartbeat 는 뒤 단위에서 이 클래스에 추가함
// 키 확인은 TenantFilter 가 먼저 한다 (실패하면 여기 오기 전에 401). 팀은 TenantContext, env 는 요청 속성으로 넘어온다 (AI-70)
@RestController
public class IngestController {

    private final IngestService ingestService;

    public IngestController(IngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping(ProtocolConstants.PATH_EVENTS)   // POST /v1/events
    public ResponseEntity<Void> postEvents(
            @RequestAttribute(TenantFilter.ENV_ATTRIBUTE) String env,   // 키로 정한 env (TenantFilter 가 넣음)
            @RequestBody EventBatch batch) {

        // 1. 검증 (100개 초과면 TooManyEventsException → 400)
        ingestService.validate(batch);

        // 2. 저장 (팀은 IngestService 가 TenantContext 에서 꺼냄. 본문의 env·팀 정보는 믿지 않음)
        ingestService.store(env, batch);

        // 3. 202 Accepted, 본문 없음
        return ResponseEntity.accepted().build();
    }
}
