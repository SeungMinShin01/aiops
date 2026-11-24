package dev.aiops.server.model.repository;

import dev.aiops.server.dto.ErrorGroupFilter;
import dev.aiops.server.model.entity.ErrorGroupEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

// AI-95 : 에러 그룹 목록의 동적 조회. QueryDSL·Specification 없이 JPQL 문자열을 조립한다 (규칙 3)
// - 값이 null 인 조건은 where 에 넣지 않는다 (null 을 파라미터로 넘기지 않으므로 DB 타입 추론 문제도 없음)
// - 값은 전부 파라미터(:env 등)로 바인딩한다. ORDER BY 는 허용값 표(lastSeen/count)에서 고른 고정 문자열만 쓴다
// - 팀 조건은 붙이지 않는다. 트랜잭션이 열릴 때 set_config 로 팀이 정해지고 RLS 가 그 팀 행만 보여 준다
// - 호출하는 쪽(서비스)의 트랜잭션 안에서 불러야 한다
@Repository
public class ErrorGroupQueryRepository {

    @PersistenceContext
    private EntityManager em;

    public Page<ErrorGroupEntity> search(ErrorGroupFilter filter, Pageable pageable) {
        String where = whereOf(filter);

        // 허용값 → 컬럼 매핑 (lastSeen → lastSeenAt, count → occurrenceCount). 같은 값일 때 순서가 흔들리지 않게 id 도 붙임
        String column = "count".equals(filter.sortField()) ? "g.occurrenceCount" : "g.lastSeenAt";
        String direction = filter.ascending() ? "ASC" : "DESC";

        TypedQuery<ErrorGroupEntity> rows = em.createQuery(
                "SELECT g FROM ErrorGroupEntity g" + where + " ORDER BY " + column + " " + direction + ", g.id DESC",
                ErrorGroupEntity.class);
        bind(rows, filter);
        rows.setFirstResult((int) pageable.getOffset());
        rows.setMaxResults(pageable.getPageSize());

        TypedQuery<Long> total = em.createQuery("SELECT COUNT(g) FROM ErrorGroupEntity g" + where, Long.class);
        bind(total, filter);

        List<ErrorGroupEntity> content = rows.getResultList();
        return new PageImpl<>(content, pageable, total.getSingleResult());
    }

    // 값이 있는 조건만 AND 로 이어 붙임
    private String whereOf(ErrorGroupFilter filter) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (filter.env() != null) {
            where.append(" AND g.firstEnv = :env");
        }
        if (filter.status() != null) {
            where.append(" AND g.status = :status");
        }
        if (filter.from() != null) {
            where.append(" AND g.lastSeenAt >= :from");
        }
        if (filter.to() != null) {
            where.append(" AND g.lastSeenAt <= :to");
        }
        return where.toString();
    }

    private void bind(Query query, ErrorGroupFilter filter) {
        if (filter.env() != null) {
            query.setParameter("env", filter.env());
        }
        if (filter.status() != null) {
            query.setParameter("status", filter.status());
        }
        if (filter.from() != null) {
            query.setParameter("from", filter.from());
        }
        if (filter.to() != null) {
            query.setParameter("to", filter.to());
        }
    }
}