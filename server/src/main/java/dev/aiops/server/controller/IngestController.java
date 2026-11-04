package dev.aiops.server.controller;

import dev.aiops.protocol.EventBatch;
import dev.aiops.protocol.Heartbeat;
import dev.aiops.protocol.HelloRequest;
import dev.aiops.protocol.ProtocolConstants;
import dev.aiops.server.security.TenantFilter;
import dev.aiops.server.service.HelloService;
import dev.aiops.server.service.IngestService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// Starter 가 보내는 에러 묶음(AI-31)·hello·heartbeat(AI-33)를 받는 문
// 키 확인은 TenantFilter 가 먼저 한다 (실패하면 여기 오기 전에 401). 팀은 TenantContext, env 는 요청 속성으로 넘어온다 (AI-70)
@RestController
public class IngestController {

    private final IngestService ingestService;
    private final HelloService helloService;

    public IngestController(IngestService ingestService, HelloService helloService) {
        this.ingestService = ingestService;
        this.helloService = helloService;
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

    // AI-33 : 앱이 켜진 뒤 1번 오는 환경 정보를 저장함
    @PostMapping(ProtocolConstants.PATH_HELLO)   // POST /v1/hello
    public ResponseEntity<Void> postHello(
            @RequestAttribute(TenantFilter.ENV_ATTRIBUTE) String env,
            @RequestBody HelloRequest hello) {
        helloService.storeHello(env, hello);
        return ResponseEntity.accepted().build();
    }

    // AI-33 : 60초마다 오는 "살아 있음" 신호를 기록함
    @PostMapping(ProtocolConstants.PATH_HEARTBEAT)   // POST /v1/heartbeat
    public ResponseEntity<Void> postHeartbeat(
            @RequestAttribute(TenantFilter.ENV_ATTRIBUTE) String env,
            @RequestBody Heartbeat heartbeat) {
        helloService.recordHeartbeat(env, heartbeat);
        return ResponseEntity.accepted().build();
    }
}
