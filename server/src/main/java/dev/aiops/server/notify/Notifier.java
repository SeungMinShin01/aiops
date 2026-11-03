package dev.aiops.server.notify;

/**
 * 새 에러 그룹을 팀에게 밖으로 알리는 부품 (⑯).
 * <p>
 * 처리 로직(ErrorProcessor)은 이 인터페이스만 안다. 디스코드 말고 슬랙·이메일이 붙어도
 * 처리 쪽 코드는 바뀌지 않는다.
 * <p>
 * 구현은 어떤 경우에도 예외를 밖으로 던지지 않는다. 알림 실패가 처리 실패가 되면 안 된다
 * (그룹은 이미 processed=true).
 */
public interface Notifier {

    /**
     * 새로 생긴 그룹 1개를 그 팀·env 채널로 알린다. 같은 그룹 재발생에는 호출하지 않는다.
     *
     * @param tenantId 그룹의 팀 번호
     * @param groupId  새로 만들어진 error_group.id
     */
    void notifyNewGroup(Long tenantId, Long groupId);
}
