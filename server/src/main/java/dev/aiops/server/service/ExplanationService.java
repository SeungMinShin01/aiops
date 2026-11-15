package dev.aiops.server.service;

import dev.aiops.server.client.LlmClient;
import dev.aiops.server.client.LlmPrompt;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 새 그룹에 LLM 설명을 붙인다 (AI-56). 그룹당 1회.
 * <ul>
 *   <li>이 클래스에는 @Transactional이 없다. 조회·저장만 ExplanationStore의 짧은 트랜잭션으로 하고, LLM 호출은 그 밖에서 한다</li>
 *   <li>설명이 없으면(실패·상한) 아무것도 저장하지 않는다. NULL로 남아야 재시도가 다시 시도한다</li>
 *   <li>응답은 그대로 저장한다. 내용을 해석해 분기하지 않는다 (규칙 13)</li>
 *   <li>예외를 밖으로 던지지 않는다. 설명 실패가 처리·알림 실패가 되면 안 된다</li>
 * </ul>
 * TenantContext는 부르는 쪽(처리 풀, 재시도 스케줄러)이 이미 set 해 둔 상태여야 한다.
 */
@Service
public class ExplanationService {

    /** explain 결과. 재시도 스케줄러가 "상한이면 멈춘다"를 판단하는 데 쓴다 (AI-57) */
    public enum Outcome { SAVED, ALREADY_DONE, SKIPPED, NO_ANSWER, QUOTA_EXCEEDED, FAILED }

    private static final Logger log = LoggerFactory.getLogger(ExplanationService.class);

    private final ExplanationStore store;
    private final LlmClient llmClient;
    private final LlmQuota quota;
    private final PromptBuilder promptBuilder = new PromptBuilder();   // 순수 계산기라 그냥 만들어 씀

    public ExplanationService(ExplanationStore store, LlmClient llmClient, LlmQuota quota) {
        this.store = store;
        this.llmClient = llmClient;
        this.quota = quota;
    }

    public Outcome explain(Long tenantId, Long groupId) {
        try {
            ExplanationStore.Loaded loaded = store.load(tenantId, groupId);          // 짧은 트랜잭션 1
            if (loaded == null) {
                log.warn("설명 생략: 그룹 없음 tenantId={} groupId={}", tenantId, groupId);
                return Outcome.SKIPPED;
            }
            if (loaded.group().getAiExplanation() != null) {
                return Outcome.ALREADY_DONE;                                        // 이미 있음 (멱등)
            }
            if (loaded.representative() == null) {
                log.warn("설명 생략: 대표 이벤트 없음 tenantId={} groupId={}", tenantId, groupId);
                return Outcome.SKIPPED;
            }
            if (!quota.tryAcquire(tenantId)) {
                log.warn("LLM 일일 상한 도달 tenant={} group={}", tenantId, groupId);
                return Outcome.QUOTA_EXCEEDED;
            }

            LlmPrompt prompt = promptBuilder.build(loaded.group(), loaded.representative());
            Optional<String> answer = llmClient.complete(prompt);                    // 트랜잭션 밖
            if (answer.isEmpty()) {
                log.info("설명 없음 (NULL 유지, 재시도에서 다시 시도) tenantId={} groupId={}", tenantId, groupId);
                return Outcome.NO_ANSWER;
            }

            boolean saved = store.saveIfEmpty(tenantId, groupId, answer.get());      // 짧은 트랜잭션 2
            log.info("설명 저장 tenantId={} groupId={} saved={}", tenantId, groupId, saved);
            return saved ? Outcome.SAVED : Outcome.ALREADY_DONE;
        } catch (RuntimeException e) {
            // 프롬프트·응답 내용이 섞일 수 있어 예외 메시지는 남기지 않는다
            log.warn("설명 실패 {} tenantId={} groupId={}", e.getClass().getSimpleName(), tenantId, groupId);
            return Outcome.FAILED;
        }
    }
}