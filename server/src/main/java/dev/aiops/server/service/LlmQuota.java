package dev.aiops.server.service;

import org.springframework.stereotype.Component;

/**
 * LLM 호출 상한 (AI-56에서는 자리만). 항상 허용한다.
 * 일일 상한은 AI-57에서 이 메서드 안을 채운다.
 */
@Component
public class LlmQuota {

    /** 이 팀이 지금 LLM을 한 번 더 불러도 되면 true */
    public boolean tryAcquire(Long tenantId) {
        return true;
    }
}