package dev.aiops.server.config;

import dev.aiops.server.security.AdminProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** AdminProperties(record)를 빈으로 등록한다 (AI-78·81). 보안 체인과 달리 웹 서버 없는 테스트에서도 뜬다 */
@Configuration
@EnableConfigurationProperties(AdminProperties.class)
public class AdminConfig {
}
