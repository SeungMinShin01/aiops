package dev.aiops.server.dto;

// AI-97 : PUT /errors/{groupId}/feedback 요청 본문 { "feedback": "UP" }. 허용값 확인은 서비스가 한다 (UP, DOWN)
public record FeedbackChange(String feedback) {
}