package com.iskeru.computeadmin.machine.event;

import com.iskeru.computeadmin.machine.repository.MachineRepository;
import com.iskeru.computeadmin.machine.service.MachineFacts;
import com.iskeru.computeadmin.machine.service.MachineFactsProbe;
import com.iskeru.computeadmin.machine.service.MachineService;
import com.iskeru.computeadmin.ssh.SshTarget;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reacts to {@link MachineReachedEvent} by running the read-only OS/cloud
 * {@link MachineFactsProbe} <strong>once per machine</strong> and applying the detected
 * tags (spec-018, source 2). The same "we just reached this machine" signal that
 * refreshes connectivity status ({@code MachineStatusUpdater}) also triggers a one-time
 * facts probe → auto-tag.
 *
 * <p><strong>Async, unbound actor, short transactions.</strong> Like the status updater
 * it is {@link Async @Async} on the {@code machineEventExecutor} pool, so it runs where
 * {@code CurrentUser} is unbound: {@link MachineService#applyDetectedFacts} is
 * system-scoped (loads by id, tags the machine's own owner) and touches no
 * UI-attributed state. The expensive SSH probe runs <em>outside</em> any transaction
 * (spec-013 H3); only the cheap guard read and the tag write use a short
 * {@link TransactionTemplate}.
 *
 * <p><strong>Once per machine / add-only.</strong> A machine whose {@code factsProbedAt}
 * is already set is skipped before the probe even runs, so auto-tagging never re-adds a
 * tag the user has since removed — the durable "add-only, user stays in control" rule.
 *
 * <p>This listener never runs or approves an action — it only reads the box and adds
 * labels. Approval stays UI-only (ARCH.md core invariant).
 *
 * <p>spec-018.
 */
@Component
public class MachineFactsTagger {

    private final MachineRepository machines;
    private final MachineFactsProbe probe;
    private final MachineService machineService;
    private final TransactionTemplate tx;

    public MachineFactsTagger(MachineRepository machines, MachineFactsProbe probe,
                              MachineService machineService,
                              PlatformTransactionManager transactionManager) {
        this.machines = machines;
        this.probe = probe;
        this.machineService = machineService;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Async("machineEventExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMachineReached(MachineReachedEvent event) {
        // Cheap guard read: resolve the SSH target only for a not-yet-probed machine,
        // so an already-probed (or vanished) machine costs no SSH round-trip and never
        // re-adds a user-removed auto-tag.
        SshTarget target = tx.execute(status -> machines.findById(event.machineId())
                .filter(machine -> machine.getFactsProbedAt() == null)
                .map(machine -> new SshTarget(machine.getHost(), machine.getPort(), machine.getLoginUser()))
                .orElse(null));
        if (target == null) {
            return;
        }
        // Read-only probe outside any transaction (spec-013 H3), then a short write tx
        // applies the add-only tags and stamps factsProbedAt.
        MachineFacts facts = probe.probe(target);
        machineService.applyDetectedFacts(event.machineId(), facts);
    }
}
