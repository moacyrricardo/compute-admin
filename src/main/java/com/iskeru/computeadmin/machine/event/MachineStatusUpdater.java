package com.iskeru.computeadmin.machine.event;

import com.iskeru.computeadmin.machine.model.MachineStatus;
import com.iskeru.computeadmin.machine.repository.MachineRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reacts to {@link MachineReachedEvent} by refreshing the machine's connection
 * status to {@link MachineStatus#ONLINE} — the <em>immediate positive feedback</em>
 * that a successful run/discovery/probe/manual-test just proved reachability,
 * without waiting for the next {@code ConnectivityCheckJob} cron tick.
 *
 * <p><strong>Async, own short transaction.</strong> The handler is
 * {@link Async @Async} on the dedicated {@code machineEventExecutor} pool, so it
 * runs on a pool thread where the {@code CurrentUser} {@link java.lang.ScopedValue}
 * is <strong>unbound</strong> — exactly like the connectivity job — and the Envers
 * revision therefore stamps {@code via = SYSTEM}, never the triggering user. It
 * re-loads the machine and writes inside its own {@link TransactionTemplate} short
 * transaction (spec-013 discipline), never joining the caller's transaction.
 *
 * <p><strong>Write-on-change / idempotent.</strong> It writes only when the status
 * actually differs, so an already-{@code ONLINE} machine produces <em>no</em> new
 * {@code machine_aud} revision — a liveness signal is not a config edit (spec-003).
 * This also collapses a burst of events (many runs against one box, or the job
 * re-announcing an already-online machine) to at most one revision, so it never
 * double-writes against the periodic job.
 *
 * <p>{@link TransactionPhase#AFTER_COMMIT} with {@code fallbackExecution = true}: a
 * publisher inside a transaction fires the handler only once that work commits;
 * publishers with no active transaction (the run pool thread, the discovery probe
 * phase, the manual-test endpoint) fall through to immediate (still async) delivery.
 *
 * <p>This listener never runs or approves an action — it only ever flips a status
 * flag. Approval stays UI-only (ARCH.md core invariant).
 *
 * <p>spec-019.
 */
@Component
public class MachineStatusUpdater {

    private final MachineRepository machines;
    private final TransactionTemplate tx;

    public MachineStatusUpdater(MachineRepository machines, PlatformTransactionManager transactionManager) {
        this.machines = machines;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Async("machineEventExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMachineReached(MachineReachedEvent event) {
        tx.executeWithoutResult(status -> machines.findById(event.machineId()).ifPresent(machine -> {
            // Write-on-change only: an already-ONLINE machine opens no machine_aud
            // revision (a liveness signal is not a config edit; spec-003), which also
            // makes a second ONLINE a no-op against the periodic job.
            if (machine.getStatus() != MachineStatus.ONLINE) {
                machine.setStatus(MachineStatus.ONLINE);
                machine.setUpdatedAt(event.at());
            }
        }));
    }
}
