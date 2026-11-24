package dev.aiops.server.model.repository;

import dev.aiops.server.model.entity.DiscordStatus;
import dev.aiops.server.model.entity.TenantEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface TenantRepository extends JpaRepository<TenantEntity, Long> {

    // ---- 관리자 팀 일괄 생성·목록 (AI-83). 고정 조건만 ----

    // 같은 기수 안에 같은 이름의 팀이 이미 있는지
    boolean existsByCohortIdAndName(Long cohortId, String name);

    // 기수의 팀 전부
    List<TenantEntity> findAllByCohortId(Long cohortId);

    // ---- 디스코드 자원 id 저장 (AI-92 DiscordProvisioner) ----
    // 자원 하나 만들 때마다 바로 저장한다 (짧은 트랜잭션, REQUIRES_NEW).
    // 디스코드에는 롤백이 없으므로 전체를 한 트랜잭션으로 묶지 않는다 — 중간에 실패해도 만든 것까지는 남는다.
    // tenant는 RLS 대상이 아니라 팀 설정 없이 갱신된다.

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("update TenantEntity t set t.discordRoleId = :value where t.id = :id")
    int updateDiscordRoleId(@Param("id") Long id, @Param("value") String value);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("update TenantEntity t set t.discordCategoryId = :value where t.id = :id")
    int updateDiscordCategoryId(@Param("id") Long id, @Param("value") String value);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("update TenantEntity t set t.discordChannelLocalId = :value where t.id = :id")
    int updateDiscordChannelLocalId(@Param("id") Long id, @Param("value") String value);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("update TenantEntity t set t.discordChannelProdId = :value where t.id = :id")
    int updateDiscordChannelProdId(@Param("id") Long id, @Param("value") String value);

    /** 웹훅 url은 비밀값. 이 컬럼에만 저장하고 로그·응답에 쓰지 않는다 (규칙 10·40) */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("update TenantEntity t set t.discordWebhookLocal = :value where t.id = :id")
    int updateDiscordWebhookLocal(@Param("id") Long id, @Param("value") String value);

    /** 웹훅 url은 비밀값. 이 컬럼에만 저장하고 로그·응답에 쓰지 않는다 (규칙 10·40) */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("update TenantEntity t set t.discordWebhookProd = :value where t.id = :id")
    int updateDiscordWebhookProd(@Param("id") Long id, @Param("value") String value);

    /** error는 "<단계명>:<status>" (최대 100자). 성공이면 null */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("update TenantEntity t set t.discordStatus = :status, t.discordError = :error where t.id = :id")
    int updateDiscordStatus(@Param("id") Long id, @Param("status") DiscordStatus status, @Param("error") String error);
}