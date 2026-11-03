package dev.aiops.server.service;

import dev.aiops.protocol.EventBatch;
import dev.aiops.protocol.ProtocolConstants;
import org.springframework.stereotype.Service;

// 수집 요청을 실제로 처리하는 곳. 지금은 검증만 있고, 저장(store)은 뒤에서 추가함
@Service
public class IngestService {

    // 묶음이 규칙에 맞는지 검사. 어기면 예외
    public void validate(EventBatch batch) {
        // 100개 초과면 거절. 빈 묶음(0개)은 정상 (버린 개수만 알리는 경우)
        if (batch.events().size() > ProtocolConstants.MAX_BATCH_SIZE) {
            throw new TooManyEventsException("too many events");
        }
    }
}