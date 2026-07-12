package com.iskeru.computeadmin.machine.service;

import com.iskeru.computeadmin.machine.event.MachineReachedEvent;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.machine.model.MachineStatus;
import com.iskeru.computeadmin.ssh.SshExecutor;
import com.iskeru.computeadmin.ssh.SshTarget;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Backs the manual "Test connection" endpoint ({@code POST /api/machines/{id}/test}),
 * finally wiring the Register screen's long-promised button.
 *
 * <p>Owner-scoped: it resolves the machine through
 * {@link MachineService#requireMachine(String)}, so another user's (or an absent)
 * machine reads as 404 — existence is never leaked. It then runs the trivial
 * {@code true} probe over the {@link SshExecutor} port now and, on success, publishes
 * a {@link MachineReachedEvent} so the async {@code MachineStatusUpdater} refreshes
 * the durable status ({@code via = SYSTEM}).
 *
 * <p>The returned {@code Machine} carries the <em>freshly observed</em> status as a
 * transient read-out for immediate operator feedback; it is deliberately <strong>not
 * persisted here</strong>. Persisting the ONLINE transition (and its audit revision)
 * is the listener's job, which keeps a liveness signal out of the UI-attributed edit
 * trail — a manual reachability check is not a config edit (spec-003). A probe that
 * finds the box down reports {@code OFFLINE}/{@code UNREACHABLE} without a write; the
 * periodic job still owns the going-away transition.
 *
 * <p>spec-019.
 */
@Service
public class ConnectionTestService {

    private final MachineService machineService;
    private final SshExecutor ssh;
    private final ApplicationEventPublisher events;

    public ConnectionTestService(MachineService machineService, SshExecutor ssh,
                                 ApplicationEventPublisher events) {
        this.machineService = machineService;
        this.ssh = ssh;
        this.events = events;
    }

    /**
     * Probes one of the current user's machines and returns it with the observed
     * status.
     *
     * @throws MachineNotFoundException 404 if the machine is absent or owned by
     *                                  another user.
     */
    public Machine test(String id) {
        Machine machine = machineService.requireMachine(id);
        SshTarget target = new SshTarget(machine.getHost(), machine.getPort(), machine.getLoginUser());
        MachineStatus observed = probe(target);
        if (observed == MachineStatus.ONLINE) {
            events.publishEvent(new MachineReachedEvent(machine.getId(), Instant.now()));
        }
        // Transient read-out only (the entity is detached — no open transaction here);
        // the durable ONLINE write + audit revision is the listener's job.
        machine.setStatus(observed);
        return machine;
    }

    private MachineStatus probe(SshTarget target) {
        try {
            return ssh.exec(target, List.of("true"), false).succeeded()
                    ? MachineStatus.ONLINE
                    : MachineStatus.OFFLINE;
        } catch (RuntimeException e) {
            return MachineStatus.UNREACHABLE;
        }
    }
}
