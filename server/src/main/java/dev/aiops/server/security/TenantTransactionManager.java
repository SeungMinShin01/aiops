package dev.aiops.server.security;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;

/**
 * 트랜잭션이 열릴 때마다 TenantContext의 팀 번호를 Postgres 변수 app.tenant_id에 <b>트랜잭션 범위로</b> 넣는다.
 * "코드의 팀 번호"와 "DB의 팀 번호"를 잇는 유일한 다리다 (AI-71).
 * <p>
 * {@code set_config('app.tenant_id', ?, true)} — 세 번째 인자 <b>true</b> = 트랜잭션이 끝나면 자동으로 사라진다 (D-016).
 * false면 세션 범위라 커넥션 풀에서 다음 요청(다른 팀)에게 값이 남는다.
 * <p>
 * 팀이 비어 있으면 아무것도 설정하지 않는다. RLS 정책의 NULLIF가 NULL을 만들어 0건이 된다 (fail closed).
 * 예외도 던지지 않는다 — 보관 삭제 같은 관리 작업은 팀 없이 트랜잭션을 열 수 있어야 한다.
 * 읽기 전용 트랜잭션도 doBegin을 지나므로 똑같이 설정된다.
 */
public class TenantTransactionManager extends JpaTransactionManager {

    static final String SET_TENANT_SQL = "SELECT set_config('app.tenant_id', ?, true)";

    public TenantTransactionManager(EntityManagerFactory emf) {
        super(emf);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition); // 커넥션 획득 + BEGIN

        Optional<Long> tenant = TenantContext.get();
        if (tenant.isEmpty()) {
            return;
        }
        try {
            // 같은 트랜잭션의 커넥션 (super.doBegin이 스레드에 묶어 둔 것). 여기서 닫지 않는다.
            Connection connection = DataSourceUtils.getConnection(getDataSource());
            try (PreparedStatement ps = connection.prepareStatement(SET_TENANT_SQL)) {
                ps.setString(1, String.valueOf(tenant.get()));
                ps.execute();
            }
        } catch (SQLException | RuntimeException ex) {
            // 팀을 못 넣은 채로 트랜잭션을 쓰게 두지 않는다. 열어 둔 트랜잭션을 되돌리고 스레드에 묶인 자원을 푼다.
            rollbackQuietly();
            doCleanupAfterCompletion(transaction);
            throw new CannotCreateTransactionException("트랜잭션에 팀 번호를 설정하지 못했다", ex);
        }
    }

    private void rollbackQuietly() {
        try {
            EntityManagerHolder holder =
                    (EntityManagerHolder) TransactionSynchronizationManager.getResource(obtainEntityManagerFactory());
            if (holder != null && holder.getEntityManager().getTransaction().isActive()) {
                holder.getEntityManager().getTransaction().rollback();
            }
        } catch (RuntimeException ignored) {
            // 정리 중 실패는 원래 예외를 가리지 않게 무시
        }
    }
}
