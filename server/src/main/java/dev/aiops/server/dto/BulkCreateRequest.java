package dev.aiops.server.dto;

import java.time.LocalDate;
import java.util.List;

// AI-83 : 팀 일괄 생성 요청. { "cohortId": 1, "teams": [ { "name": "3기-1조", "projectEndDate": "2027-02-20" }, … ] }
public record BulkCreateRequest(Long cohortId, List<TeamInput> teams) {

    // AI-83 : 만들 팀 한 개. projectEndDate 는 "2027-02-20" 형식 (비워도 됨)
    public record TeamInput(String name, LocalDate projectEndDate) {
    }
}