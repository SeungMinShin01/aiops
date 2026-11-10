package dev.aiops.server.config;

import dev.aiops.server.security.TenantTransactionManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * 기본 JpaTransactionManager를 팀 번호를 DB에 넘기는 TenantTransactionManager로 바꾼다 (AI-71).
 * 이 Bean이 있으면 Spring Boot 자동 구성의 기본 트랜잭션 매니저는 만들어지지 않는다 (@ConditionalOnMissingBean).
 */
@Configuration
public class JpaConfig {

    @Bean(name = "transactionManager")
    public PlatformTransactionManager transactionManager(EntityManagerFactory emf, DataSource dataSource) {
        TenantTransactionManager tm = new TenantTransactionManager(emf);
        tm.setDataSource(dataSource); // doBegin이 같은 커넥션을 DataSourceUtils로 꺼낼 수 있게
        return tm;
    }
}
