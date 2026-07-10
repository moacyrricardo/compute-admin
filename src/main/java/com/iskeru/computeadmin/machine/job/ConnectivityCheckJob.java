package com.iskeru.computeadmin.machine.job;

import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.machine.model.MachineStatus;
import com.iskeru.computeadmin.machine.repository.MachineRepository;
import com.iskeru.computeadmin.ssh.ExecResult;
import com.iskeru.computeadmin.ssh.SshExecutor;
import com.iskeru.computeadmin.ssh.SshTarget;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Refreshes every machine's connection status on a schedule by running a trivial
 * {@code true} over SSH. Runs <strong>system-scoped</strong> across the whole
 * fleet (not per-user): it reads {@code MachineRepository.findAll()}, the
 * deliberate owner-bypassing call. Because no {@code AuthContext} is bound, the
 * audited status change records {@code via = SYSTEM}.
 *
 * <p>spec-003.
 */
@Component
public class ConnectivityCheckJob {

    private final MachineRepository machines;
    private final SshExecutor ssh;

    public ConnectivityCheckJob(MachineRepository machines, SshExecutor ssh) {
        this.machines = machines;
        this.ssh = ssh;
    }

    @Scheduled(cron = "${ca.connectivity.cron:0 */5 * * * *}")
    @Transactional
    public void checkAll() {
        List<Machine> all = machines.findAll();
        for (Machine machine : all) {
            machine.setStatus(probe(machine));
            machine.setUpdatedAt(Instant.now());
        }
    }

    private MachineStatus probe(Machine machine) {
        try {
            ExecResult result = ssh.exec(
                    new SshTarget(machine.getHost(), machine.getPort(), machine.getLoginUser()),
                    List.of("true"), false);
            return result.succeeded() ? MachineStatus.ONLINE : MachineStatus.OFFLINE;
        } catch (RuntimeException e) {
            return MachineStatus.UNREACHABLE;
        }
    }
}
