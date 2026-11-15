package dev.aiops.server.service;

import dev.aiops.protocol.ErrorEvent;
import dev.aiops.server.model.entity.ErrorEventEntity;
import dev.aiops.server.model.entity.ErrorGroupEntity;
import dev.aiops.server.model.repository.ErrorEventRepository;
import dev.aiops.server.model.repository.ErrorGroupRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 설명에 필요한 DB 접근 두 가지만 맡는다 (AI-56). 각각 짧은 트랜잭션.
 * ExplanationService와 다른 Bean인 이유: 같은 클래스 안에서 부르면 @Transactional이 적용되지 않는다(프록시를
 * 안 탄다).
 * LLM 호출은 여기 없다 — 트랜잭션 안에서 부르면 DB 연결을 최대 20초 붙잡는다.
 */
@Service
public class ExplanationStore {

    /** 그룹과 대표 이벤트. 대표 이벤트가 없으면 representative는 null */
    public record Loaded(ErrorGroupEntity group, ErrorEvent representative) {
    }

    private final ErrorGroupRepository groups;
    private final ErrorEventRepository events;
    private final ObjectMapper objectMapper;

    public ExplanationStore(ErrorGroupRepository groups, ErrorEventRepository events, ObjectMapper objectMapper) {
        this.groups = groups;
        this.events = events;
        this.objectMapper = objectMapper;
    }

    /** 그룹 + 가장 최근 이벤트 1건. 그룹이 없거나 다른 팀 것이면 null */
    @Transactional(readOnly = true)
    public Loaded load(Long tenantId, Long groupId) {
        ErrorGroupEntity group = groups.findById(groupId).orElse(null);
        if (group == null || !group.getTenantId().equals(tenantId)) {
            return null;
        }
        ErrorEventEntity latest = events.findFirstByGroupIdOrderByReceivedAtDescIdDesc(groupId).orElse(null);
        ErrorEvent representative = null;
        if (latest != null) {
            representative = objectMapper.readValue(latest.getPayload(), ErrorEvent.class);
        }
        return new Loaded(group, representative);
    }

    /** 설명이 비어 있을 때만 저장. 저장했으면 true, 이미 채워져 있었으면 false */
    @Transactional
    public boolean saveIfEmpty(Long tenantId, Long groupId, String explanation) {
        return groups.updateExplanationIfNull(groupId, tenantId, explanation, Instant.now()) == 1;
    }
}