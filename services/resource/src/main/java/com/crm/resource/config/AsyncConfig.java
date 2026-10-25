package com.crm.resource.config;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;

/** Tasks carry a persisted correlation ID so recovery can restore the original trace. */
@Configuration
public class AsyncConfig {

    @Bean("sagaExecutor")
    public ThreadPoolTaskExecutor sagaExecutor(
            @Value("${saga.pool.core:4}") int core,
            @Value("${saga.pool.max:8}") int max,
            @Value("${saga.pool.queue:100}") int queue) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queue);
        executor.setThreadNamePrefix("saga-");
        executor.setTaskDecorator(mdcPropagatingDecorator());
        executor.initialize();
        return executor;
    }

    /** Restore the previous MDC when returning a reused executor thread. */
    private TaskDecorator mdcPropagatingDecorator() {
        return runnable -> {
            Map<String, String> captured = MDC.getCopyOfContextMap();
            return () -> {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                try {
                    if (captured != null) {
                        MDC.setContextMap(captured);
                    }
                    runnable.run();
                } finally {
                    if (previous != null) {
                        MDC.setContextMap(previous);
                    } else {
                        MDC.clear();
                    }
                }
            };
        };
    }
}
