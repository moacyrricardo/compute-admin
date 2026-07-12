package com.iskeru.computeadmin.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
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
 * <p><strong>Graceful drain (spec-016).</strong> On shutdown the pool does an
 * orderly {@code shutdown()} and awaits in-flight runs up to
 * {@code ca.run.shutdown-await-seconds} (kept below
 * {@code spring.lifecycle.timeout-per-shutdown-phase}) so a run that finishes within
 * the window records its <em>real</em> {@code DONE}/{@code FAILED} outcome. Threads
 * stay {@code daemon}: past the window they are abandoned at JVM exit rather than
 * hanging shutdown, and the boot {@code RunReconciler} backstops anything left
 * non-terminal. The bean is {@code @DependsOn} the SSH executor so it is destroyed
 * (and drained) <em>before</em> the shared {@link org.apache.sshd.client.SshClient}
 * is stopped, so a draining run is not torn off its session mid-flight. Because the
 * SSH bean name differs by profile ({@code minaSshExecutor} vs
 * {@code localDevSshExecutor}), the dependency is declared per profile.
 *
 * <p>spec-005; graceful drain in spec-016.
 */
@Configuration
public class AsyncConfig {

    private final int coreSize;
    private final int maxSize;
    private final int queueCapacity;
    private final int shutdownAwaitSeconds;

    public AsyncConfig(@Value("${ca.run.core-pool-size:2}") int coreSize,
                       @Value("${ca.run.max-pool-size:8}") int maxSize,
                       @Value("${ca.run.queue-capacity:100}") int queueCapacity,
                       @Value("${ca.run.shutdown-await-seconds:20}") int shutdownAwaitSeconds) {
        this.coreSize = coreSize;
        this.maxSize = maxSize;
        this.queueCapacity = queueCapacity;
        this.shutdownAwaitSeconds = shutdownAwaitSeconds;
    }

    /**
     * The run pool for the default (real SSH) profiles. {@code @DependsOn} the
     * {@code minaSshExecutor} bean so the pool drains before the shared SSH client
     * is stopped (spec-016 §3).
     */
    @Bean("runExecutor")
    @Profile("!localssh")
    @DependsOn("minaSshExecutor")
    public TaskExecutor runExecutor() {
        return buildRunExecutor();
    }

    /**
     * The run pool for the {@code localssh} profile, where the SSH port is
     * {@link com.iskeru.computeadmin.ssh.LocalDevSshExecutor} (no shared client to
     * stop). {@code @DependsOn} that bean to keep the same drain-then-stop ordering.
     */
    @Bean("runExecutor")
    @Profile("localssh")
    @DependsOn("localDevSshExecutor")
    public TaskExecutor runExecutorLocal() {
        return buildRunExecutor();
    }

    private TaskExecutor buildRunExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("run-");
        // Drain in-flight runs on shutdown, bounded so shutdown can never hang: await
        // up to N seconds, then abandon the (daemon) threads at JVM exit. Runs that
        // exceed the window are backstopped by the boot RunReconciler. spec-016.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(shutdownAwaitSeconds);
        // Daemon threads so an idle pool never blocks JVM shutdown; combined with the
        // bounded await above, this is the balanced drain-then-give-up choice (spec-016).
        executor.setDaemon(true);
        executor.initialize();
        return executor;
    }
}
