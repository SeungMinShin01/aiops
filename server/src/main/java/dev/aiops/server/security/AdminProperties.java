package dev.aiops.server.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

/**
 * 관리자 GitHub id 목록 (A-06). 값은 환경변수 AIOPS_ADMIN_GITHUB_IDS (쉼표 구분 숫자) 하나뿐이다.
 * DB 컬럼·하드코딩 없음. 비어 있으면 관리자 없음 (첫 로그인 사용자를 관리자로 만드는 편의 기능은 두지 않는다).
 * /api/me의 isAdmin(AI-78)과 AdminGuard(AI-81)가 이 한 곳을 같이 쓴다. 환경변수를 두 군데서 따로 파싱하지 않는다.
 */
@ConfigurationProperties("aiops.admin")
public record AdminProperties(Set<Long> githubIds) {

    public AdminProperties {
        githubIds = githubIds == null ? Set.of() : Set.copyOf(githubIds);
    }

    public boolean isAdmin(long githubId) {
        return githubIds.contains(githubId);
    }
}
