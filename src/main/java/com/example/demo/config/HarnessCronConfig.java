package com.example.demo.config;

import com.example.demo.cron.CronTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableScheduling
public class HarnessCronConfig {

    private static final Logger log = LoggerFactory.getLogger(HarnessCronConfig.class);

    private final HarnessProperties properties;
    private final CronTaskService cronTaskService;

    public HarnessCronConfig(HarnessProperties properties, @Lazy CronTaskService cronTaskService) {
        this.properties = properties;
        this.cronTaskService = cronTaskService;
    }

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        int poolSize = properties.getCron().getPoolSize();
        scheduler.setPoolSize(poolSize > 0 ? poolSize : 5);
        scheduler.setThreadNamePrefix("harness-cron-");
        scheduler.initialize();
        log.info("Initialized Harness TaskScheduler with pool size: {}", scheduler.getPoolSize());
        return scheduler;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeScheduledIntents() {
        if (!properties.getCron().isEnabled()) {
            log.info("Harness Cron scheduler is disabled by configuration");
            return;
        }

        var scheduledIntents = properties.getCron().getScheduledIntents();
        if (scheduledIntents == null || scheduledIntents.isEmpty()) {
            return;
        }

        log.info("Initializing {} pre-configured scheduled cron intents from application properties", scheduledIntents.size());
        for (var config : scheduledIntents) {
            cronTaskService.scheduleJob(config.getId(), config.getIntent(), config.getCronExpression(), config.getIntervalSeconds());
        }
    }
}
