package dev.aiops.server.service;

import java.util.List;

// 같은 원인 묶기(⑭) 담당 쪽이 구현하는 약속. 저장된 이벤트 id 만 받음 (D-081)
public interface ErrorProcessor {
    void process(Long tenantId, List<Long> eventIds);
}