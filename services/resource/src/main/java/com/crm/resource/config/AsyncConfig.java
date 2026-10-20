package com.crm.resource.config;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;

/**
 * The saga executor, with an MDC-propagating TaskDecorator (nfr-design.md 3.3) — this is "layer
 * (a)" of the two-mechanism fix for the async-boundary correlation-ID problem. Layer (b) is the
 * correlationId field carried on ProvisioningTask/TerminationTask, re-seeded as the saga's first
 * statement; that part lives in ProvisioningSaga/TerminationService, not here.
 *
 * <p>Sized for Challenge 1: 20 concurrent provisioning requests can each dispatch one saga task;
 * core=4/max=8/queue=100 lets them queue rather than reject (infrastructure-design.md / units.md
 * config variables).
 */
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

    /**
     * Captures the MDC on the WEB thread that submits the task, restores it on the POOL thread
     * that runs it, and restores whatever the pool thread had before, afterward — Tomcat reuses
     * threads, so leaving a stale MDC behind would leak one request's trace into the next.
     */
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
