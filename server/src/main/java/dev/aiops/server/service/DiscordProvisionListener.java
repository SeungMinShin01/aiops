package dev.aiops.server.service;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 팀 생성 커밋 후 → 디스코드 채널 자동 생성 (AI-92).
 * 팀 생성 응답이 디스코드를 기다리지 않게 커밋 뒤(AFTER_COMMIT), 별도 스레드(@Async)에서 돈다.
 * 실패해도 팀 생성은 이미 성공이다 — provision은 예외를 밖으로 던지지 않고 discord_status=FAILED로 남긴다.
 */
@Component
public class DiscordProvisionListener {

    private final DiscordProvisioner provisioner;

    public DiscordProvisionListener(DiscordProvisioner provisioner) {
        this.provisioner = provisioner;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTeamCreated(TeamCreated event) {
        provisioner.provision(event.tenantId());
    }

    /** 정지 커밋 후 → 웹훅 2개 삭제 (채널·역할은 보관). 정지 API는 디스코드를 기다리지 않는다 (AI-94) */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTeamSuspended(TeamSuspended event) {
        provisioner.suspend(event.tenantId());
    }

    /** 재개 커밋 후 → provision 재실행. 멱등이라 역할·채널은 건너뛰고 웹훅만 새로 만든다 (AI-94) */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTeamResumed(TeamResumed event) {
        provisioner.provision(event.tenantId());
    }
}
