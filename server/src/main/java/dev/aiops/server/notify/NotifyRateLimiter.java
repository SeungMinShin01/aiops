package dev.aiops.server.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 팀별 분당 알림 상한. 에러가 쏟아져도 디스코드가 도배되지 않게 막는다.
 * <p>
 * 메모리 카운터로 충분하다 (서버 1대, 넘치면 버리는 게 목적이라 정확한 계수가 아님).
 * 상한은 팀마다 따로 센다. 전역 상한을 두면 한 팀이 다른 팀 알림을 막는다.
 * 초과분은 모아 두지 않고 버린다 (콘솔에서 다 보인다).
 */
@Component
public class NotifyRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(NotifyRateLimiter.class);

    private final int perMinute;
    private final Clock clock;
    private final Map<Long, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public NotifyRateLimiter(@Value("${aiops.notify.per-minute:10}") int perMinute) {
        this(perMinute, Clock.systemUTC());
    }

    /** 테스트에서 시계를 움직이기 위한 생성자. */
    NotifyRateLimiter(int perMinute, Clock clock) {
        this.perMinute = perMinute;
        this.clock = clock;
    }

    /**
     * 이번 분에 이 팀의 알림을 하나 더 보내도 되는지.
     *
     * @return 상한 이내면 true, 넘었으면 false (넘은 첫 번째에만 WARN 1줄)
     */
    public boolean tryAcquire(Long tenantId) {
        long minute = clock.millis() / 60_000;
        Window window = windows.compute(tenantId,
                (id, old) -> (old == null || old.minute != minute) ? new Window(minute) : old);
        int count = window.count.incrementAndGet();
        if (count <= perMinute) {
            return true;
        }
        if (count == perMinute + 1) {
            log.warn("알림 분당 상한 초과, 이번 분 나머지는 버림 tenantId={} 상한={}", tenantId, perMinute);
        }
        return false;
    }

    private static final class Window {
        final long minute;
        final AtomicInteger count = new AtomicInteger();

        Window(long minute) {
            this.minute = minute;
        }
    }
}
