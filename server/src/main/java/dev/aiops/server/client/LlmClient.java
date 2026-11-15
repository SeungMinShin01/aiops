package dev.aiops.server.client;

import java.util.Optional;

/**
 * LLM을 부르는 단 하나의 창구 (AI-54). 다른 코드는 이 인터페이스만 주입받는다.
 * 실패(타임아웃, 4xx/5xx, 응답 형식 오류)는 예외가 아니라 Optional.empty()다.
 * 재시도는 여기서 하지 않는다 (재처리 스케줄러 몫).
 */
public interface LlmClient {

    Optional<String> complete(LlmPrompt prompt);
}