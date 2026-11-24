package dev.aiops.server.dto;

import dev.aiops.server.model.entity.GroupStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.data.domain.PageRequest;

// AI-95 : 목록 조회 조건. 요청 문자열을 여기서 허용값만 통과시킨다 (그 밖은 InvalidRequestException → 400)
// null 은 "그 조건 없음". sort 는 검사를 마친 "필드,방향" (예: "lastSeen,desc")
public record ErrorGroupFilter(String env, GroupStatus status, Instant from, Instant to, String sort) {

    public static final int MAX_SIZE = 100;                     // 페이지 크기 상한
    private static final String DEFAULT_SORT = "lastSeen,desc"; // 정렬을 안 주면 최근 발생순
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");  // 날짜만 받을 때의 하루 기준
    private static final List<String> ENVS = List.of("local", "prod");
    private static final List<String> SORT_FIELDS = List.of("lastSeen", "count");
    private static final List<String> SORT_DIRECTIONS = List.of("asc", "desc");

    // 요청 값이 허용 범위 밖일 때 (컨트롤러가 400 으로 바꿈)
    public static class InvalidRequestException extends RuntimeException {
        public InvalidRequestException(String message) {
            super(message);
        }
    }

    // AI-107 : 페이지 번호·크기 검사. 팀용 목록과 관리자용 목록이 같은 규칙을 쓰도록 한 곳에 둔다
    public static PageRequest pageRequest(int page, int size) {
        if (page < 0) {
            throw new InvalidRequestException("page 는 0 이상");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new InvalidRequestException("size 는 1 이상 " + MAX_SIZE + " 이하");
        }
        return PageRequest.of(page, size);
    }

    public static ErrorGroupFilter fromRequest(String env, String status, String from, String to, String sort) {
        Instant fromTime = parseTime(from, false, "from");
        Instant toTime = parseTime(to, true, "to");
        if (fromTime != null && toTime != null && fromTime.isAfter(toTime)) {
            throw new InvalidRequestException("from 이 to 보다 늦다");
        }
        return new ErrorGroupFilter(parseEnv(env), parseStatus(status), fromTime, toTime, parseSort(sort));
    }

    // "lastSeen" 또는 "count" (검사를 마친 값이라 그대로 쓸 수 있다)
    public String sortField() {
        return sort.substring(0, sort.indexOf(','));
    }

    public boolean ascending() {
        return sort.endsWith(",asc");
    }

    private static String parseEnv(String env) {
        if (env == null || env.isBlank()) {
            return null;
        }
        if (!ENVS.contains(env)) {
            throw new InvalidRequestException("env 는 local 또는 prod");
        }
        return env;
    }

    private static GroupStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return GroupStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("status 는 OPEN, RESOLVED, IGNORED 중 하나");
        }
    }

    // 날짜만(2026-11-01)이면 from 은 그날 00:00:00, to 는 그날 23:59:59 (한국 시간). 시각까지 주면 그대로 (예: 2026-11-01T00:00:00Z)
    private static Instant parseTime(String text, boolean endOfDay, String name) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String value = text.trim();
        try {
            if (value.length() == 10) {
                LocalDate date = LocalDate.parse(value);
                if (endOfDay) {
                    return date.plusDays(1).atStartOfDay(KST).toInstant().minusSeconds(1);
                }
                return date.atStartOfDay(KST).toInstant();
            }
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            throw new InvalidRequestException(name + " 는 2026-11-01 또는 2026-11-01T00:00:00Z 형식");
        }
    }

    private static String parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return DEFAULT_SORT;
        }
        String[] parts = sort.split(",", -1);
        String field = parts[0].trim();
        String direction = parts.length == 2 ? parts[1].trim() : "desc";
        if (parts.length > 2 || !SORT_FIELDS.contains(field) || !SORT_DIRECTIONS.contains(direction)) {
            throw new InvalidRequestException("sort 는 lastSeen 또는 count 에 asc/desc (예: lastSeen,desc)");
        }
        return field + "," + direction;
    }
}