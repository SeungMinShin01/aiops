package dev.aiops.server.dto;

import java.time.LocalDate;

// AI-96 : 하루치 발생 횟수 (한국 시간 기준 날짜). 원본이 없는 날은 count 0
public record DailyCount(LocalDate date, long count) {
}