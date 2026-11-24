package dev.aiops.server.service;

import dev.aiops.server.model.repository.ErrorEventRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// AI-61 : 오래된 error_event 를 "한 묶음" 지우는 일만 함. 묶음마다 트랜잭션이 따로라서
// 한 묶음 지우고 바로 확정(커밋)됨 (한꺼번에 다 지울 때 생기는 락·WAL 부담을 피함)
@Service
public class ErrorEventPurger {

    private final ErrorEventRepository errorEventRepository;

    public ErrorEventPurger(ErrorEventRepository errorEventRepository) {
        this.errorEventRepository = errorEventRepository;
    }

    // AI-61 : cutoff 보다 오래된 env 의 이벤트를 최대 batchSize 개 지우고 지운 개수를 돌려줌
    @Transactional
    public int purgeBatch(String env, Instant cutoff, int batchSize) {
        return errorEventRepository.deleteOlderThan(env, cutoff, batchSize);
    }
}