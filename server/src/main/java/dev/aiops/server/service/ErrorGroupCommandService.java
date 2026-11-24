package dev.aiops.server.service;

import dev.aiops.server.dto.GroupFeedbackResult;
import dev.aiops.server.dto.GroupStatusResult;
import dev.aiops.server.model.entity.Feedback;
import dev.aiops.server.model.entity.GroupStatus;
import dev.aiops.server.model.repository.ErrorGroupRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// AI-97 : 에러 그룹의 상태 변경과 AI 설명 평가. 팀은 MembershipGuard 가 정한 TenantContext 로 이미 정해져 있고
// 트랜잭션이 열릴 때 set_config 가 불려서 RLS 가 그 팀 행만 보이게 한다. 다른 팀 그룹은 UPDATE 가 0건이 되어 "없음"과 똑같이 404.
// 엔티티를 읽어 고치지 않고 이 칸만 UPDATE 한다 (그 사이 들어온 발생 횟수를 옛 값으로 덮어쓰지 않으려고)
@Service
public class ErrorGroupCommandService {

    // 요청 값이 허용값이 아닐 때 (400)
    public static class InvalidValueException extends RuntimeException {
        public InvalidValueException(String message) {
            super(message);
        }
    }

    // 설명이 아직 없는 그룹에 평가를 남기려 할 때 (409). 평가할 대상이 없다
    public static class NoExplanationException extends RuntimeException {
        public NoExplanationException() {
            super("AI 설명이 아직 없는 그룹");
        }
    }

    private final ErrorGroupRepository groups;

    public ErrorGroupCommandService(ErrorGroupRepository groups) {
        this.groups = groups;
    }

    @Transactional
    public GroupStatusResult changeStatus(Long groupId, String rawStatus) {
        GroupStatus status = parseStatus(rawStatus);
        if (groups.updateStatus(groupId, status) == 0) {
            throw new ErrorGroupQueryService.GroupNotFoundException();   // 없거나 다른 팀 그룹
        }
        return new GroupStatusResult(groupId, status);
    }

    @Transactional
    public GroupFeedbackResult giveFeedback(Long groupId, String rawFeedback) {
        Feedback feedback = parseFeedback(rawFeedback);
        if (groups.updateFeedbackIfExplained(groupId, feedback) == 0) {
            // 0건의 이유가 둘: 그룹이 없다 / 있는데 설명이 아직 없다. RLS 때문에 다른 팀 그룹은 existsById 도 false → 404
            if (!groups.existsById(groupId)) {
                throw new ErrorGroupQueryService.GroupNotFoundException();
            }
            throw new NoExplanationException();
        }
        return new GroupFeedbackResult(groupId, feedback);
    }

    private static GroupStatus parseStatus(String raw) {
        if (raw != null) {
            for (GroupStatus status : GroupStatus.values()) {
                if (status.name().equals(raw)) {
                    return status;
                }
            }
        }
        throw new InvalidValueException("status 는 OPEN, RESOLVED, IGNORED 중 하나");
    }

    private static Feedback parseFeedback(String raw) {
        if (raw != null) {
            for (Feedback feedback : Feedback.values()) {
                if (feedback.name().equals(raw)) {
                    return feedback;
                }
            }
        }
        throw new InvalidValueException("feedback 은 UP, DOWN 중 하나");
    }
}