package com.iskeru.computeadmin.machine.job;

import com.iskeru.computeadmin.machine.event.MachineReachedEvent;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.machine.model.MachineStatus;
import com.iskeru.computeadmin.machine.repository.MachineRepository;
import com.iskeru.computeadmin.ssh.ExecResult;
import com.iskeru.computeadmin.ssh.SshExecutor;
import com.iskeru.computeadmin.ssh.SshTarget;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Refreshes every machine's connection status on a schedule by running a trivial
 * {@code true} over SSH. Runs <strong>system-scoped</strong> across the whole fleet
 * (not per-user): it reads {@link MachineRepository#findAll()}, the deliberate
 * owner-bypassing call. Because no {@code AuthContext} is bound on the scheduler
 * thread, the audited status change records {@code via = SYSTEM}.
 *
 * <p><strong>Resource scoping (spec-013).</strong> There is no fleet-wide
 * {@code @Transactional}. The job snapshots the fleet into scalar
 * {@link Probe}s, runs the SSH probes <em>outside any transaction</em> with bounded
 * concurrency ({@code ca.connectivity.concurrency}, each probe bounded by the
 * existing {@code ca.ssh.*-timeout-seconds}), then applies each status change
 * <em>on the job thread</em> in its own short transaction. Applying re-loads and
 * compares the status <em>inside</em> that transaction, so an unchanged probe writes
 * nothing and opens no {@code machine_aud} revision (spec-003). The probe pool
 * threads do no DB/audit work — and {@code CurrentUser} is a thread-confined
 * {@code ScopedValue} that does not propagate into them anyway — so the
 * {@code via = SYSTEM} stamp comes only from the job thread's write.
 *
 * <p><strong>Going-away authority (spec-019).</strong> This job remains the sole
 * authority for the ONLINE → OFFLINE/UNREACHABLE transition (a machine that dies is
 * detected within one cron interval). The event path added in spec-019 only provides
 * <em>immediate</em> positive ONLINE feedback from real usage; on an ONLINE probe the
 * job also publishes a {@link MachineReachedEvent} so other listeners share that
 * signal, but the write-on-change status update keeps it from double-writing.
 *
 * <p>spec-003; concurrency + transaction scoping in spec-013; reachability event in
 * spec-019.
 */
@Component
public class ConnectivityCheckJob {

    /** A scalar snapshot of one machine — safe to carry across the no-transaction probe phase. */
    private record Probe(String id, SshTarget target, MachineStatus currentStatus) {
    }

    /** The outcome of probing one machine: its id and the freshly observed status. */
    private record Probed(String id, MachineStatus status) {
    }

    private final MachineRepository machines;
    private final SshExecutor ssh;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final int concurrency;

    public ConnectivityCheckJob(MachineRepository machines, SshExecutor ssh,
                                PlatformTransactionManager transactionManager,
                                ApplicationEventPublisher events,
                                @Value("${ca.connectivity.concurrency:8}") int concurrency) {
        this.machines = machines;
        this.ssh = ssh;
        this.tx = new TransactionTemplate(transactionManager);
        this.events = events;
        this.concurrency = Math.max(1, concurrency);
    }

    @Scheduled(cron = "${ca.connectivity.cron:0 */5 * * * *}")
    public void checkAll() {
        List<Probe> fleet = machines.findAll().stream()
                .map(m -> new Probe(m.getId(),
                        new SshTarget(m.getHost(), m.getPort(), m.getLoginUser()),
                        m.getStatus()))
                .toList();
        if (fleet.isEmpty()) {
            return;
        }
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(concurrency, fleet.size()), probeThreadFactory());
        try {
            List<Future<Probed>> futures = new ArrayList<>(fleet.size());
            for (Probe probe : fleet) {
                futures.add(pool.submit(() -> new Probed(probe.id(), probe(probe.target()))));
            }
            for (Future<Probed> future : futures) {
                Probed probed = await(future);
                apply(probed);
                // Announce reachability so other listeners (spec-018's facts probe)
                // react to the same signal the run/discovery paths emit. The status
                // write above already set ONLINE, so MachineStatusUpdater's reload is a
                // write-on-change no-op — the two never double-write. spec-019.
                if (probed.status() == MachineStatus.ONLINE) {
                    events.publishEvent(new MachineReachedEvent(probed.id(), Instant.now()));
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** Applies one probe result in its own short transaction, on the job thread. */
    private void apply(Probed result) {
        tx.executeWithoutResult(status -> machines.findById(result.id()).ifPresent(machine -> {
            // Only mutate on an actual status change: a liveness probe is not a config
            // edit, so an unchanged result must leave the machine clean and produce no
            // machine_aud revision (no via=SYSTEM audit noise). spec-003.
            if (result.status() != machine.getStatus()) {
                machine.setStatus(result.status());
                machine.setUpdatedAt(Instant.now());
            }
        }));
    }

    private MachineStatus probe(SshTarget target) {
        try {
            ExecResult result = ssh.exec(target, List.of("true"), false);
            return result.succeeded() ? MachineStatus.ONLINE : MachineStatus.OFFLINE;
        } catch (RuntimeException e) {
            return MachineStatus.UNREACHABLE;
        }
    }

    private static Probed await(Future<Probed> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while probing the fleet", e);
        } catch (ExecutionException e) {
            // probe() already maps every RuntimeException to UNREACHABLE, so a task
            // failure here is unexpected; surface it rather than swallow it.
            throw new IllegalStateException("Connectivity probe failed", e.getCause());
        }
    }

    private static ThreadFactory probeThreadFactory() {
        AtomicLong counter = new AtomicLong();
        return runnable -> {
            Thread thread = new Thread(runnable, "connectivity-probe-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
