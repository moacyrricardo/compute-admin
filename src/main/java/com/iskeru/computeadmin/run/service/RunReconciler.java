package com.iskeru.computeadmin.run.service;

import com.iskeru.computeadmin.common.AuthContext;
import com.iskeru.computeadmin.common.CurrentUser;
import com.iskeru.computeadmin.run.model.Run;
import com.iskeru.computeadmin.run.model.RunStatus;
import com.iskeru.computeadmin.run.repository.RunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Boot-time reconciler — the authoritative fix for orphaned runs (spec-016). On
 * {@code SIGTERM}/crash the run engine cannot guarantee every in-flight run reaches a
 * terminal state (the bounded drain in {@code AsyncConfig} is best-effort). Any run
 * still {@code QUEUED}/{@code RUNNING} at the <em>next</em> boot therefore belongs to
 * the previous, now-dead process — never a live peer, under the single-instance
 * {@code AUTO_SERVER} invariant (spec-016 context) — so it is unconditionally marked
 * terminal {@link RunStatus#INTERRUPTED}.
 *
 * <p>The sweep is <strong>system-scoped</strong> across all owners (mirroring
 * {@code ConnectivityCheckJob}'s fleet {@code findAll()}); {@link Run} is not
 * {@code @Audited}, so binding {@link AuthContext#system()} is for consistency, not
 * correctness.
 *
 * <p>spec-016.
 */
@Component
public class RunReconciler {

    private static final Logger log = LoggerFactory.getLogger(RunReconciler.class);

    /** The non-terminal statuses an orphaned run can be found in at boot. */
    private static final List<RunStatus> NON_TERMINAL = List.of(RunStatus.QUEUED, RunStatus.RUNNING);

    /** Sentinel appended to a reconciled run's stderr; the true remote outcome is unknown. */
    public static final String SENTINEL =
            "Run abandoned by a server shutdown; the remote command's actual outcome is unknown.";

    private final RunRepository runs;

    public RunReconciler(RunRepository runs) {
        this.runs = runs;
    }

    /**
     * Runs the sweep once the context is ready, under the system context. Annotated
     * {@code @Transactional} (not {@link #reconcile()}, which it would self-invoke and
     * so bypass the proxy) because the event multicaster calls this through the bean
     * proxy, so the transaction advice applies to the whole boot sweep.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void onApplicationReady() {
        CurrentUser.runWhere(AuthContext.system(), () -> {
            reconcileInternal();
            return null;
        });
    }

    /**
     * Marks every run left {@code QUEUED}/{@code RUNNING} by the previous process
     * terminal: {@link RunStatus#INTERRUPTED}, {@code finishedAt = now},
     * {@code exitCode = -1}, and the {@link #SENTINEL} note appended to stderr.
     * Returns the count reconciled. The transactional entry point used directly by
     * tests; the boot path enters through {@link #onApplicationReady()}.
     */
    @Transactional
    public int reconcile() {
        return reconcileInternal();
    }

    private int reconcileInternal() {
        List<Run> orphaned = runs.findByStatusIn(NON_TERMINAL);
        if (orphaned.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        for (Run run : orphaned) {
            run.setStatus(RunStatus.INTERRUPTED);
            run.setFinishedAt(now);
            run.setExitCode(-1);
            run.setStderr(appendSentinel(run.getStderr()));
        }
        runs.saveAll(orphaned);
        log.info("Run reconciliation: marked {} orphaned run(s) INTERRUPTED on boot", orphaned.size());
        return orphaned.size();
    }

    private static String appendSentinel(String existing) {
        if (existing == null || existing.isEmpty()) {
            return SENTINEL;
        }
        return existing.endsWith("\n") ? existing + SENTINEL : existing + "\n" + SENTINEL;
    }
}
