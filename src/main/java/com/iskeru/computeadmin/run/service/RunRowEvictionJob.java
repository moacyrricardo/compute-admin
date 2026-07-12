package com.iskeru.computeadmin.run.service;

import com.iskeru.computeadmin.run.model.RunStatus;
import com.iskeru.computeadmin.run.repository.RunRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Scheduled reaper that bounds the {@code run} table (spec-022, extending spec-013's
 * in-memory {@link RunOutputEvictionJob}). Monitor polling persists one parent run
 * plus one child per {@code (app-name, port)} item every few seconds, so terminal
 * rows must be pruned or the log grows without bound.
 *
 * <p>The deletion unit is a <strong>top-level run</strong> — a fan-out parent or a
 * standalone run ({@code parent_run_id IS NULL}) — together with its children. Two
 * bounds are applied, both touching <em>only terminal</em> rows (a {@code QUEUED}/
 * {@code RUNNING} row, and any still-live child, is never deleted):
 *
 * <ol>
 *   <li><strong>retention</strong> ({@code ca.run.row-retention}, default 24h) —
 *       terminal top-level runs whose {@code finishedAt} is older than the window;</li>
 *   <li><strong>per-action cap</strong> ({@code ca.run.rows-per-action-max}) — beyond
 *       the cap, the oldest terminal top-level runs of an action.</li>
 * </ol>
 *
 * <p>Children are deleted <em>before</em> their parent (the {@code parent_run_id}
 * self-FK forbids the reverse). Because a parent is terminal only once all its
 * children are terminal, selecting terminal top-level rows never orphans a live
 * child. {@code Run} is an append-only, non-audited log (ARCH.md), so bulk deletion
 * needs no Envers handling. The {@code Instant asOf} seam mirrors the hub's eviction
 * seam so a unit test can drive pruning without waiting on the schedule.
 *
 * <p>spec-022.
 */
@Component
public class RunRowEvictionJob {

    private static final Set<RunStatus> TERMINAL =
            Set.of(RunStatus.DONE, RunStatus.FAILED, RunStatus.INTERRUPTED);

    private final RunRepository runs;
    private final Duration retention;
    private final int rowsPerActionMax;

    public RunRowEvictionJob(RunRepository runs,
                             @Value("${ca.run.row-retention:24h}") Duration retention,
                             @Value("${ca.run.rows-per-action-max:500}") int rowsPerActionMax) {
        this.runs = runs;
        this.retention = retention;
        this.rowsPerActionMax = rowsPerActionMax;
    }

    @Scheduled(cron = "${ca.run.row-eviction-cron:0 */10 * * * *}")
    public void evict() {
        prune(Instant.now());
    }

    /**
     * Prunes terminal top-level runs (and their children) past the retention window
     * and beyond the per-action cap, as of {@code asOf}. The test seam.
     */
    @Transactional
    public void prune(Instant asOf) {
        Instant cutoff = asOf.minus(retention);
        deleteUnits(runs.findTopLevelTerminalIdsFinishedBefore(TERMINAL, cutoff));

        if (rowsPerActionMax >= 0) {
            for (String actionId : runs.findActionIdsWithTerminalTopLevelCountAbove(TERMINAL, rowsPerActionMax)) {
                List<String> newestFirst = runs.findTerminalTopLevelIdsNewestFirst(actionId, TERMINAL);
                if (newestFirst.size() > rowsPerActionMax) {
                    // Keep the newest `cap`; the tail is the oldest terminal runs to prune.
                    deleteUnits(newestFirst.subList(rowsPerActionMax, newestFirst.size()));
                }
            }
        }
    }

    /** Deletes each top-level run's children first, then the runs themselves. */
    private void deleteUnits(List<String> topLevelIds) {
        if (topLevelIds.isEmpty()) {
            return;
        }
        runs.deleteByParentRunIdIn(topLevelIds);
        runs.deleteByIdIn(topLevelIds);
    }
}
