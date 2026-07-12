package com.iskeru.computeadmin.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ExecutorConfigurationSupport;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The run pool participates in a bounded, orderly shutdown (spec-016 §2): it awaits
 * in-flight runs on shutdown, up to {@code ca.run.shutdown-await-seconds}, so a run
 * finishing within the window records its real outcome. Asserted by inspecting the
 * built {@link ThreadPoolTaskExecutor}'s drain flags (no getters exist, so via the
 * {@link ExecutorConfigurationSupport} fields Spring sets from the setters).
 *
 * <p>spec-016.
 */
class AsyncConfigTest {

    @Test
    void runExecutor_DrainsInFlightRunsBoundedByAwaitKey() throws Exception {
        AsyncConfig config = new AsyncConfig(2, 8, 100, 17);
        TaskExecutor executor = config.runExecutor();
        try {
            ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) executor;
            assertThat(boolField(pool, "waitForTasksToCompleteOnShutdown")).isTrue();
            assertThat(longField(pool, "awaitTerminationMillis")).isEqualTo(17_000L);
        } finally {
            ((ThreadPoolTaskExecutor) executor).shutdown();
        }
    }

    private static boolean boolField(Object target, String name) throws Exception {
        return (boolean) readField(target, name);
    }

    private static long longField(Object target, String name) throws Exception {
        return (long) readField(target, name);
    }

    /** Reads a field declared anywhere in the object's class hierarchy. */
    private static Object readField(Object target, String name) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
