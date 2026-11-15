package dev.aiops.server.client;

import java.util.Optional;

/**
 * 키가 없을 때 쓰는 가짜 (AI-54). 로컬 개발·테스트용. 외부를 부르지 않고 고정 문장을 돌려준다.
 * 실제 설명과 헷갈리지 않게 "(가짜 응답)"을 붙인다.
 */
public class FakeLlmClient implements LlmClient {

    static final String FIXED = """
            원인 후보: (가짜 응답) LLM 키가 설정되지 않아 실제 분석을 하지 않았습니다.
            확인 방법: 서버 환경변수 AIOPS_LLM_API_KEY를 설정했는지 확인하세요.
            해결 방향: 키를 설정하고 서버를 다시 시작하면 실제 설명이 생성됩니다.""";

    @Override
    public Optional<String> complete(LlmPrompt prompt) {
        return Optional.of(FIXED);
    }
}