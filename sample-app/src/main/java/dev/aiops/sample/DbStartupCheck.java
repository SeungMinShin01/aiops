package dev.aiops.sample;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 시작할 때 DB에 한 번 접속한다. 실제 팀 앱에서 JPA·Flyway가 시작 시 DB에 붙는 것을 흉내 낸다.
 * 커넥션 풀은 처음 쓸 때 연결하므로, 이게 없으면 fail 프로필에서도 앱이 정상으로 떠 버린다.
 * 여기서 실패하면 → 앱 시작 실패 → Starter가 STARTUP 에러로 전송 (5단계)
 */
@Component
public class DbStartupCheck implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    public DbStartupCheck(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbc.queryForObject("select 1", Integer.class);
    }
}