package dev.aiops.server.service;

import java.util.List;

// "이 이벤트들이 저장됐다"는 알림. 엔티티가 아니라 id 목록만 담음
// (처리하는 쪽은 별도 스레드라서 자기 트랜잭션에서 DB 를 다시 읽음)
public record ErrorsStored(
        Long tenantId,          // 어느 팀의 이벤트인지
        List<Long> eventIds     // 저장된 error_event 행들의 id
) {
}