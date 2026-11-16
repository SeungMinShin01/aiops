package dev.aiops.server.service;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * LLM 하루 호출 상한 (AI-57). 전체 팀 합산.
 * <ul>
 *   <li>카운터는 DB(llm_usage). 메모리에 두면 재시작하거나 서버가 2대가 되는 순간 상한이 무의미해진다</li>
 *   <li>"세기"와 "상한 검사"를 SQL 한 문장으로 한다. 따로 하면 동시에 들어온 호출이 둘 다 통과한다</li>
 *   <li>오늘 날짜는 DB가 아니라 서버에서 한국 시간으로 구해 넘긴다 (DB 시간대 설정에 기대지 않으려고)</li>
 *   <li>상한에 걸려도 예외를 던지지 않는다. false만 돌려준다</li>
 * </ul>
 */
@Component
public class LlmQuota {

    static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    // 오늘 행이 없으면 1로 만들고, 있으면 상한보다 작을 때만 +1. 올렸으면 행이 돌아오고, 상한이면 아무 행도 안 돌아온다
    private static final String ACQUIRE_SQL = """
            INSERT INTO llm_usage (day, call_count) VALUES (CAST(:day AS date), 1)
            ON CONFLICT (day) DO UPDATE SET call_count = llm_usage.call_count + 1
            WHERE llm_usage.call_count < :limit
            RETURNING call_count
            """;

    private final EntityManager em;
    private final int dailyLimit;

    public LlmQuota(EntityManager em, @Value("${aiops.llm.daily-limit:200}") int dailyLimit) {
        this.em = em;
        this.dailyLimit = dailyLimit;
    }

    /** 지금 LLM을 한 번 더 불러도 되면 true (그리고 1회를 쓴 것으로 센다). tenantId는 팀별 상한을 넣을 때를 위한 자리 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryAcquire(Long tenantId) {
        return tryAcquire(LocalDate.now(ZONE), dailyLimit);
    }

    /** 날짜와 상한을 직접 주는 형태 (테스트가 오늘 칸을 건드리지 않게) */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryAcquire(LocalDate day, int limit) {
        if (limit <= 0) {
            return false;   // INSERT 쪽에는 상한 검사가 없어서 0 이하는 여기서 막는다
        }
        List<?> rows = em.createNativeQuery(ACQUIRE_SQL)
                .setParameter("day", day)
                .setParameter("limit", limit)
                .getResultList();
        return !rows.isEmpty();
    }
}