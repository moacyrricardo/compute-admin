package com.iskeru.computeadmin.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The bounded thread pool that backs the run engine (spec-005). Approved actions
 * run <strong>asynchronously</strong> off this pool so the request that submits a
 * run returns immediately with a {@code QUEUED} run id while the command executes
 * and streams. The pool is deliberately bounded (core/max/queue) — it is the seam
 * where a per-machine cap and a global quota land when S7 is hardened.
 *
 * <p>Exposed under the {@code runExecutor} bean name; {@code RunService} injects it
 * by that qualifier (a test swaps in a synchronous executor of the same
 * {@link TaskExecutor} type). Bounds are configurable via {@code ca.run.*} with
 * in-code defaults.
 *
 * <p>spec-005.
 */
@Configuration
public class AsyncConfig {

    private final int coreSize;
    private final int maxSize;
    private final int queueCapacity;

    public AsyncConfig(@Value("${ca.run.core-pool-size:2}") int coreSize,
                       @Value("${ca.run.max-pool-size:8}") int maxSize,
                       @Value("${ca.run.queue-capacity:100}") int queueCapacity) {
        this.coreSize = coreSize;
        this.maxSize = maxSize;
        this.queueCapacity = queueCapacity;
    }

    @Bean("runExecutor")
    public TaskExecutor runExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("run-");
        // Daemon threads so an idle pool never blocks JVM shutdown (a local instance;
        // graceful drain of in-flight runs is a v-next concern alongside S7).
        executor.setDaemon(true);
        executor.initialize();
        return executor;
    }
}
