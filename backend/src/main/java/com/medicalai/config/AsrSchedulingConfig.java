package com.medicalai.config;

import java.util.concurrent.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class AsrSchedulingConfig {
    @Bean public ThreadPoolTaskScheduler publicAsrScheduler() { return scheduler("public-asr-"); }
    @Bean public ThreadPoolTaskScheduler localAsrScheduler() { return scheduler("local-asr-"); }
    // Keep unrelated @Scheduled jobs on their own default scheduler.
    @Bean public ThreadPoolTaskScheduler taskScheduler() { return scheduler("business-jobs-"); }

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService asrLeaseHeartbeat() {
        return Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "asr-lease-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    private ThreadPoolTaskScheduler scheduler(String prefix) {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(prefix);
        return scheduler;
    }
}
