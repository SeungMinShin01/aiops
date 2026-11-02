package dev.aiops.sample;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

/*
 * Starter를 시험하려고 일부러 고장 내는 엔드포인트 모음.
 * /boom/caught를 빼면 예외를 잡지 않는다. Spring이 ERROR 로그를 남기게 둔다.
 */
@RestController
public class BoomController {

    private static final Logger log = LoggerFactory.getLogger(BoomController.class);

    private final JdbcTemplate jdbc;

    public BoomController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /* 정상 응답. Starter가 응답을 느리게 하지 않는지 비교용 */
    @GetMapping("/ok")
    public Map<String, String> ok() {
        return Map.of("status", "ok");
    }

    /* 런타임 에러 */
    @GetMapping("/boom/npe")
    public String npe() {
        String s = null;
        return String.valueOf(s.length());
    }

    /* 없는 테이블 조회 → BadSqlGrammarException (원인 체인에 PSQLException) */
    @GetMapping("/boom/sql")
    public Object sql() {
        return jdbc.queryForList("select * from no_such_table");
    }

    /* 원인이 3단으로 걸린 예외 → causes 체인 확인용 */
    @GetMapping("/boom/nested")
    public String nested() {
        throw new RuntimeException("outer",
                new IllegalStateException("middle",
                        new IOException("inner")));
    }

    /* 응답은 200인데 로그에만 ERROR가 남는 경우 */
    @GetMapping("/boom/caught")
    public Map<String, String> caught() {
        try {
            Integer.parseInt("abc");
        } catch (NumberFormatException e) {
            log.error("처리 실패", e);
        }
        return Map.of("status", "ok", "note", "에러는 로그에만 남음");
    }

    /* 메시지에 민감정보 → 서버에 ***로 도착해야 함 (마스킹 확인용) */
    @GetMapping("/boom/sensitive")
    public String sensitive() {
        throw new IllegalStateException(
                "login failed password=1234 user=test@example.com token=abc.def.ghi");
    }

    /* WARN만, 예외 없음 → 수집되면 안 됨 */
    @GetMapping("/boom/warn")
    public Map<String, String> warn() {
        log.warn("경고만 남김 (수집되면 안 됨)");
        return Map.of("status", "ok");
    }
}