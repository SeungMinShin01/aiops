package dev.aiops.server.model.repository;

import dev.aiops.server.model.entity.AppUserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

// 로그인 사용자 (V3, RLS 없음 — 팀을 알기 전에 조회한다, D-023)
public interface AppUserRepository extends JpaRepository<AppUserEntity, Long> {

    // GitHub 로그인 upsert (AI-78). 사용자 식별은 github_id(숫자). login은 바뀔 수 있다
    Optional<AppUserEntity> findByGithubId(Long githubId);
}
