package dev.aiops.server.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 키 해시 → (팀, env) 짧은 캐시 (AI-74). 요청마다 api_key를 조회하지 않게 한다.
 * <ul>
 *   <li>캐시 키는 SHA-256 해시. 키 원문은 어디에도 두지 않는다 (규칙 10)</li>
 *   <li>TTL은 짧게(기본 60초). 폐기는 evict로 즉시 지운다 — TTL은 "evict를 놓쳤을 때"의 상한일 뿐</li>
 *   <li>"없는 키"는 넣지 않는다 (음성 캐싱 금지 — 새로 발급한 키가 TTL 동안 401이 되는 문제)</li>
 *   <li>크기 상한을 넘으면 만료된 항목부터, 그래도 넘치면 곧 만료될 항목부터 지운다</li>
 * </ul>
 * <b>한계</b>: 메모리 캐시라 서버 인스턴스가 2대 이상이면 한 대에서 evict해도 다른 인스턴스에는 전파되지 않는다.
 * MVP는 서버 1대이므로 문제없고, 그 경우에도 다른 인스턴스는 최대 TTL(60초) 뒤에 반영된다.
 * 인스턴스를 늘리면(v0.5) 공유 캐시나 폐기 이벤트 방송이 필요하다.
 */
@Component
public class ApiKeyCache {

    static final int DEFAULT_MAX_ENTRIES = 10_000;

    private final Duration ttl;
    private final Clock clock;
    private final int maxEntries;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    private record Entry(ResolvedKey value, Instant expiresAt) {
    }

    @Autowired
    public ApiKeyCache(@Value("${aiops.apikey.cache-ttl:60s}") Duration ttl) {
        this(ttl, Clock.systemUTC(), DEFAULT_MAX_ENTRIES);
    }

    ApiKeyCache(Duration ttl, Clock clock, int maxEntries) {
        this.ttl = ttl;
        this.clock = clock;
        this.maxEntries = maxEntries;
    }

    /** 살아 있는 항목이면 값, 없거나 만료됐으면 null */
    public ResolvedKey get(String keyHash) {
        Entry e = entries.get(keyHash);
        if (e == null) {
            return null;
        }
        if (!e.expiresAt().isAfter(clock.instant())) {
            entries.remove(keyHash, e);
            return null;
        }
        return e.value();
    }

    /** 인증에 성공한 키만 넣는다 */
    public void put(String keyHash, ResolvedKey value) {
        if (entries.size() >= maxEntries) {
            cleanUp();
        }
        entries.put(keyHash, new Entry(value, clock.instant().plus(ttl)));
    }

    /** 키 폐기 커밋 뒤 호출. 다음 요청부터 DB를 다시 본다 */
    public void evict(String keyHash) {
        entries.remove(keyHash);
    }

    int size() {
        return entries.size();
    }

    private void cleanUp() {
        Instant now = clock.instant();
        entries.entrySet().removeIf(en -> !en.getValue().expiresAt().isAfter(now));
        int over = entries.size() - maxEntries + 1; // 새 항목 1개 자리
        if (over > 0) {
            entries.entrySet().stream()
                    .sorted(Comparator.comparing((Map.Entry<String, Entry> en) -> en.getValue().expiresAt()))
                    .limit(over)
                    .map(Map.Entry::getKey)
                    .toList()
                    .forEach(entries::remove);
        }
    }
}
