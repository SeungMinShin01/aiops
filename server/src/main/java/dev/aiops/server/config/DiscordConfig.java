package dev.aiops.server.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** DiscordProperties(record)를 빈으로 등록한다. */
@Configuration
@EnableConfigurationProperties(DiscordProperties.class)
public class DiscordConfig {
}
