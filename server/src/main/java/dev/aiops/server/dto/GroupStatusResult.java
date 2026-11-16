package dev.aiops.server.dto;

import dev.aiops.server.model.entity.GroupStatus;

// AI-97 : 상태를 바꾼 결과. 화면이 목록·상세를 다시 부르지 않고 바로 반영할 수 있게 바뀐 값만 돌려준다
public record GroupStatusResult(Long id, GroupStatus status) {
}