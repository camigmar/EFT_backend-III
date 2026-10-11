package com.banco.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class BatchReejecucionConfig {

    // Hilo propio para las reanudaciones automaticas de jobs FAILED: corren fuera del hilo de la peticion HTTP
    // y de a una por replica (los jobs ya paralelizan internamente).
    @Bean
    public TaskScheduler batchReejecucionScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("batch-reejecucion-");
        scheduler.initialize();
        return scheduler;
    }
}
