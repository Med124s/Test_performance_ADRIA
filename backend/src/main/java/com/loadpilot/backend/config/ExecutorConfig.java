package com.loadpilot.backend.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Executeur a threads virtuels qui fait tourner les executions et leurs utilisateurs virtuels. */
@Configuration
public class ExecutorConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService loadTestExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
