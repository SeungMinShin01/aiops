package dev.aiops.server.controller;

import dev.aiops.server.dto.ConnectionResponse;
import dev.aiops.server.service.ConnectionService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 연결 확인 API (AI-98). Starter가 붙어 있는지 env별로 보여준다.
 * MembershipGuard(/api/teams/**)가 팀원인지 확인하고 TenantContext를 정한다 → 이 팀 데이터만 RLS로 보인다.
 * 콘솔이 5초마다 폴링하므로 캐시 금지 (no-store).
 */
@RestController
public class ConnectionController {

    private final ConnectionService connectionService;

    public ConnectionController(ConnectionService connectionService) {
        this.connectionService = connectionService;
    }

    // 200 { local: { state, lastSeenAt, hostCount }, prod: { state, lastSeenAt, hostCount } }
    @GetMapping("/api/teams/{teamId}/connection")
    public ResponseEntity<ConnectionResponse> connection(@PathVariable Long teamId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(connectionService.status(teamId));
    }
}
