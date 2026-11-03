package dev.aiops.server.notify;

import dev.aiops.server.model.entity.ErrorGroupEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DiscordEmbedBuilderTest {

    private final DiscordEmbedBuilder builder = new DiscordEmbedBuilder();

    private static ErrorGroupEntity group(String exceptionType, String topFrame, String env) {
        return new ErrorGroupEntity(1L, "f".repeat(64), exceptionType, topFrame, env);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> embed(Map<String, Object> body) {
        return ((List<Map<String, Object>>) body.get("embeds")).get(0);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> fields(Map<String, Object> body) {
        return (List<Map<String, Object>>) embed(body).get("fields");
    }

    private static Object valueOf(Map<String, Object> body, String name) {
        return fields(body).stream().filter(f -> name.equals(f.get("name")))
                .findFirst().map(f -> f.get("value")).orElse(null);
    }

    @Test
    void 제목은_예외_간단_이름이다() {
        Map<String, Object> body = builder.build(group("java.lang.NullPointerException", "a.B.c(B.java)", "local"));

        assertThat(embed(body).get("title")).isEqualTo("새 에러 · NullPointerException");
    }

    @Test
    void 위치_env_횟수_첫발생_필드가_들어간다() {
        Map<String, Object> body = builder.build(group("java.lang.IllegalStateException", "com.team.UserService.find(UserService.java)", "prod"));

        assertThat(valueOf(body, "위치")).isEqualTo("com.team.UserService.find(UserService.java)");
        assertThat(valueOf(body, "env")).isEqualTo("prod");
        assertThat(valueOf(body, "횟수")).isEqualTo("1");
        assertThat((String) valueOf(body, "첫 발생")).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}");
    }

    @Test
    void 설명이_없으면_설명_준비_중() {
        Map<String, Object> body = builder.build(group("java.lang.NullPointerException", null, "local"));

        assertThat(valueOf(body, "AI 설명")).isEqualTo(DiscordEmbedBuilder.PENDING);
        assertThat(valueOf(body, "원인 후보")).isNull();
    }

    @Test
    void 설명_세_칸을_나눠서_넣는다() {
        ErrorGroupEntity g = group("java.lang.NullPointerException", null, "local");
        g.explain("## 원인 후보\nuser가 null\n\n## 확인 방법\nfindById 결과 확인\n\n## 해결 방향\nOptional 처리");

        Map<String, Object> body = builder.build(g);

        assertThat(valueOf(body, "원인 후보")).isEqualTo("user가 null");
        assertThat(valueOf(body, "확인 방법")).isEqualTo("findById 결과 확인");
        assertThat(valueOf(body, "해결 방향")).isEqualTo("Optional 처리");
        assertThat(valueOf(body, "AI 설명")).isNull();
    }

    @Test
    void 세_칸으로_못_나누면_통째로_한_칸() {
        ErrorGroupEntity g = group("java.lang.NullPointerException", null, "local");
        g.explain("형식이 다른 설명");

        Map<String, Object> body = builder.build(g);

        assertThat(valueOf(body, "AI 설명")).isEqualTo("형식이 다른 설명");
    }

    @Test
    void 위치가_없으면_빈_값_대신_대시() {
        Map<String, Object> body = builder.build(group("java.lang.NullPointerException", null, "local"));

        assertThat(valueOf(body, "위치")).isEqualTo("-");
    }

    @Test
    void 디스코드_글자_수_제한에_맞게_자른다() {
        ErrorGroupEntity g = group("a." + "X".repeat(400), "f".repeat(2000), "local");
        g.explain("y".repeat(3000));

        Map<String, Object> body = builder.build(g);

        assertThat((String) embed(body).get("title")).hasSizeLessThanOrEqualTo(DiscordEmbedBuilder.TITLE_MAX);
        fields(body).forEach(f ->
                assertThat((String) f.get("value")).hasSizeLessThanOrEqualTo(DiscordEmbedBuilder.FIELD_VALUE_MAX));
    }
}
