package com.iskeru.computeadmin.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring's scheduling and async support so {@code @Scheduled} jobs (the
 * connectivity check) and {@code @Async} work run. Introduced by spec-003 for the
 * connectivity job; later specs (005 run engine) reuse the async executor.
 *
 * <p>spec-003.
 */
@Configuration
@EnableScheduling
@EnableAsync
public class SchedulingConfig {
}
