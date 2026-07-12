package com.iskeru.computeadmin.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * The bounded pool that backs the async {@code machine/event} listeners (spec-019).
 * {@link com.iskeru.computeadmin.machine.event.MachineStatusUpdater} runs here off
 * the request/run threads, so its Envers write lands on a thread where
 * {@code CurrentUser} is unbound ({@code via = SYSTEM}).
 *
 * <p>Deliberately <strong>single-threaded</strong> by default (core = max = 1): the
 * status-refresh write is tiny and idempotent, so serialising events keeps ordering
 * predictable and avoids piling threads onto trivial no-op writes. Overflow beyond
 * the queue runs on the calling thread ({@link ThreadPoolExecutor.CallerRunsPolicy})
 * rather than being rejected, so a burst never drops a "machine reached" signal.
 * Threads are daemon so an idle pool never blocks JVM shutdown.
 *
 * <p>Referenced by name from {@code @Async("machineEventExecutor")}. It is a second
 * {@link TaskExecutor} bean beside {@code runExecutor}; every {@code @Async}/
 * {@code @Qualifier} site names its pool explicitly, so the two never collide.
 *
 * <p>spec-019.
 */
@Configuration
public class MachineEventConfig {

    private final int coreSize;
    private final int maxSize;
    private final int queueCapacity;

    public MachineEventConfig(@Value("${ca.machine-event.core-pool-size:1}") int coreSize,
                              @Value("${ca.machine-event.max-pool-size:1}") int maxSize,
                              @Value("${ca.machine-event.queue-capacity:100}") int queueCapacity) {
        this.coreSize = coreSize;
        this.maxSize = maxSize;
        this.queueCapacity = queueCapacity;
    }

    @Bean("machineEventExecutor")
    public TaskExecutor machineEventExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("machine-event-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setDaemon(true);
        executor.initialize();
        return executor;
    }
}
