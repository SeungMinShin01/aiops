package dev.aiops.server.notify;

import dev.aiops.server.model.entity.ErrorGroupEntity;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 에러 그룹 → 디스코드 웹훅 본문(JSON으로 바뀔 Map).
 * <p>
 * 스택 전체는 넣지 않는다 (디스코드 글자 수 제한, 자세한 내용은 콘솔에서).
 * 디스코드 제한: 제목 256자, 필드 값 1,024자, 필드 값은 비어 있으면 안 된다.
 */
@Component
public class DiscordEmbedBuilder {

    static final int TITLE_MAX = 256;
    static final int FIELD_VALUE_MAX = 1024;
    static final int COLOR_RED = 0xE74C3C;
    static final String PENDING = "설명 준비 중";

    /** 7단계 출력 세 칸의 이름. 설명 TEXT 안에서 이 순서로 나온다고 가정한다. */
    static final List<String> SECTIONS = List.of("원인 후보", "확인 방법", "해결 방향");

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    public Map<String, Object> build(ErrorGroupEntity group) {
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(field("위치", group.getTopFrame(), false));
        fields.add(field("env", group.getFirstEnv(), true));
        fields.add(field("횟수", String.valueOf(group.getOccurrenceCount()), true));
        fields.add(field("첫 발생", format(group.getFirstSeenAt()), true));
        fields.addAll(explanationFields(group.getAiExplanation()));

        Map<String, Object> embed = new LinkedHashMap<>();
        embed.put("title", truncate("새 에러 · " + simpleName(group.getExceptionType()), TITLE_MAX));
        embed.put("color", COLOR_RED);
        embed.put("fields", fields);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("embeds", List.of(embed));
        return body;
    }

    /** java.lang.NullPointerException → NullPointerException */
    static String simpleName(String exceptionType) {
        if (exceptionType == null || exceptionType.isBlank()) {
            return "알 수 없는 예외";
        }
        int dot = exceptionType.lastIndexOf('.');
        return dot >= 0 ? exceptionType.substring(dot + 1) : exceptionType;
    }

    /**
     * 설명 TEXT → 필드.
     * 비어 있음 → "설명 준비 중" 한 칸 / 세 칸 이름을 순서대로 찾음 → 세 칸 / 못 찾음 → 통째로 한 칸.
     */
    static List<Map<String, Object>> explanationFields(String explanation) {
        if (explanation == null || explanation.isBlank()) {
            return List.of(field("AI 설명", PENDING, false));
        }
        int[] starts = new int[SECTIONS.size()];
        int from = 0;
        for (int i = 0; i < SECTIONS.size(); i++) {
            int at = explanation.indexOf(SECTIONS.get(i), from);
            if (at < 0) {
                return List.of(field("AI 설명", explanation, false));
            }
            starts[i] = at;
            from = at + SECTIONS.get(i).length();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < SECTIONS.size(); i++) {
            int valueStart = starts[i] + SECTIONS.get(i).length();
            int valueEnd = (i + 1 < SECTIONS.size()) ? starts[i + 1] : explanation.length();
            result.add(field(SECTIONS.get(i), clean(explanation.substring(valueStart, valueEnd)), false));
        }
        return result;
    }

    /** "원인 후보: ..."나 "## 원인 후보\n..." 같은 앞뒤 표시를 걷어낸다. */
    private static String clean(String raw) {
        return raw.replaceAll("^[\\s:：*#\\-]+", "").replaceAll("[\\s*#]+$", "");
    }

    private static Map<String, Object> field(String name, String value, boolean inline) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("name", name);
        f.put("value", (value == null || value.isBlank()) ? "-" : truncate(value, FIELD_VALUE_MAX));
        f.put("inline", inline);
        return f;
    }

    private static String format(Instant time) {
        return time == null ? "-" : TIME.format(time);
    }

    static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
