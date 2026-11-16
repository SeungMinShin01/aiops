package dev.aiops.server.controller.admin;

import dev.aiops.server.dto.DiscordRetryResponse;
import dev.aiops.server.model.entity.TenantEntity;
import dev.aiops.server.model.repository.TenantRepository;
import dev.aiops.server.service.AdminApiException;
import dev.aiops.server.service.DiscordProvisioner;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 디스코드 채널 재시도 (AI-92). 관리자 화면의 [채널 재시도] 버튼.
 * <ul>
 *   <li>관리자 여부는 AdminGuard가 이미 검사했고, 호출 기록은 AdminAccessLogInterceptor가 자동으로 남긴다</li>
 *   <li>새 로직 없이 팀 생성 때와 같은 DiscordProvisioner.provision을 부른다. 멱등이라 끊긴 단계부터 이어서 만들고
 *       채널이 중복으로 생기지 않는다. 연타는 팀별 잠금이 막는다 (IN_PROGRESS)</li>
 *   <li>버튼을 누른 관리자가 결과를 바로 봐야 하므로 비동기가 아니라 끝날 때까지 기다린다 (디스코드 호출 몇 번, 수 초)</li>
 * </ul>
 */
@RestController
public class AdminDiscordController {

    private final DiscordProvisioner provisioner;
    private final TenantRepository tenants;

    public AdminDiscordController(DiscordProvisioner provisioner, TenantRepository tenants) {
        this.provisioner = provisioner;
        this.tenants = tenants;
    }

    // 200 { teamId, result, discordStatus, discordError }
    @PostMapping("/api/admin/teams/{teamId}/discord/retry")
    public DiscordRetryResponse retry(@PathVariable Long teamId) {
        DiscordProvisioner.Result result = provisioner.provision(teamId);
        switch (result) {
            case NOT_FOUND -> throw new AdminApiException(HttpStatus.NOT_FOUND, "TEAM_NOT_FOUND", "팀이 없어요");
            case SUSPENDED -> throw new AdminApiException(HttpStatus.CONFLICT, "TEAM_SUSPENDED",
                    "정지된 팀이에요. 재개하면 채널이 자동으로 다시 연결돼요");
            case IN_PROGRESS -> throw new AdminApiException(HttpStatus.CONFLICT, "IN_PROGRESS",
                    "이 팀의 채널을 만드는 중이에요. 잠시 후 다시 확인하세요");
            case NOT_CONFIGURED -> throw new AdminApiException(HttpStatus.SERVICE_UNAVAILABLE, "DISCORD_NOT_CONFIGURED",
                    "서버에 디스코드 봇 설정이 없어요");
            default -> {
                // READY / ALREADY_READY / FAILED: 지금 상태를 다시 읽어 그대로 보여준다
            }
        }
        TenantEntity tenant = tenants.findById(teamId)
                .orElseThrow(() -> new AdminApiException(HttpStatus.NOT_FOUND, "TEAM_NOT_FOUND", "팀이 없어요"));
        return new DiscordRetryResponse(teamId, result.name(), tenant.getDiscordStatus(), tenant.getDiscordError());
    }
}
