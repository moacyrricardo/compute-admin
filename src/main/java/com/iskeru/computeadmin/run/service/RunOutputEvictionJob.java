package com.iskeru.computeadmin.run.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Scheduled reaper that releases the memory held by finished-run output channels.
 * It simply forwards the current wall clock to {@link RunOutputHub#evict(Instant)},
 * which removes only <em>complete</em> channels past the retention TTL and trims the
 * remainder to the LRU cap. The {@code Instant asOf} seam lives on the hub so a unit
 * test can drive eviction directly without waiting on this schedule.
 *
 * <p>spec-013.
 */
@Component
public class RunOutputEvictionJob {

    private final RunOutputHub hub;

    public RunOutputEvictionJob(RunOutputHub hub) {
        this.hub = hub;
    }

    @Scheduled(cron = "${ca.run.output-eviction-cron:0 * * * * *}")
    public void evict() {
        hub.evict(Instant.now());
    }
}
