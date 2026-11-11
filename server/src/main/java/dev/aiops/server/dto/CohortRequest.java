package dev.aiops.server.dto;

import java.time.LocalDate;

// AI-82 : 기수 생성 요청. 날짜는 "2026-09-01" 형식. 날짜는 비워도 되고, 둘 다 있으면 endDate >= startDate
public record CohortRequest(String name, LocalDate startDate, LocalDate endDate) {
}