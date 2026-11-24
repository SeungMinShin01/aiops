package dev.aiops.server.client;

/**
 * LLM에 보내는 글 한 쌍 (AI-54).
 *
 * @param system 역할·출력 형식 지시
 * @param user   에러 정보 (마스킹된 것만)
 */
public record LlmPrompt(String system, String user) {
}