package dev.aiops.server.dto;

import java.time.Instant;
import java.util.List;

// AI-96 : 상세 화면의 "최근 발생" 한 건. 저장된 원본(payload)에서 꺼낸 값 그대로 (서버가 다시 마스킹하거나 만들어 넣지 않는다)
// stack = "클래스.메서드(파일:줄)" 문자열 목록 (저장된 최대 30줄 그대로), causes = "예외타입: 메시지" 목록 (바깥 → 안쪽)
// phase = STARTUP / RUNTIME (없으면 null)
public record RecentEvent(Instant occurredAt, String message, List<String> stack, List<String> causes,
                          String thread, String phase) {
}