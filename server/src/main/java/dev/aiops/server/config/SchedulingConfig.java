package dev.aiops.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

// AI-53 : @Scheduled 를 켜고, 처리 풀(aiops-proc-)과 분리된 스케줄러 전용 스레드 풀을 씀
@Configuration
@EnableScheduling
public class SchedulingConfig implements SchedulingConfigurer {

    // AI-53 : 스케줄러 전용 스레드 2개. 이름표 aiops-sched-
    @Bean
    public ThreadPoolTaskScheduler aiopsScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("aiops-sched-");
        return scheduler;
    }

    // @Scheduled 메서드들이 위 풀에서 돌도록 지정
    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.setTaskScheduler(aiopsScheduler());
    }
}