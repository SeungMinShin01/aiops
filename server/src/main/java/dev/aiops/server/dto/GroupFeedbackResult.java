package dev.aiops.server.dto;

import dev.aiops.server.model.entity.Feedback;

// AI-97 : AI 설명 평가를 남긴 결과
public record GroupFeedbackResult(Long id, Feedback feedback) {
}