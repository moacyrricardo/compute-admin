package com.iskeru.computeadmin.machine;

import com.iskeru.computeadmin.auth.model.AppUser;
import com.iskeru.computeadmin.auth.repository.AppUserRepository;
import com.iskeru.computeadmin.common.AuthContext;
import com.iskeru.computeadmin.common.CurrentUser;
import com.iskeru.computeadmin.machine.job.ConnectivityCheckJob;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.machine.model.MachineStatus;
import com.iskeru.computeadmin.machine.repository.MachineRepository;
import com.iskeru.computeadmin.machine.service.MachineService;
import com.iskeru.computeadmin.machine.service.MachineService.RegisterMachineInput;
import com.iskeru.computeadmin.ssh.ExecResult;
import com.iskeru.computeadmin.ssh.OutputSink;
import com.iskeru.computeadmin.ssh.SshExecutor;
import com.iskeru.computeadmin.ssh.SshTarget;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.envers.AuditReaderFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the connectivity job's audit hygiene (spec-003): a liveness probe is not
 * a config edit, so {@link ConnectivityCheckJob#checkAll()} must write a machine —
 * and therefore open a {@code machine_aud} revision — <strong>only when the probed
 * status actually differs</strong>. An unchanged probe leaves the machine clean and
 * produces no new revision, keeping {@code via = SYSTEM} noise out of the audit
 * trail.
 *
 * <p>Mirrors {@link MachineAuditTest}: Envers persists at commit, so this opts out
 * of the rollback wrapper ({@code NOT_SUPPORTED}) and drives each job run in its own
 * committing transaction, then reads revisions back via the Envers reader.
 *
 * <p>spec-003.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(MachineService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ConnectivityCheckJobTest {

    @Autowired
    private MachineService machineService;

    @Autowired
    private MachineRepository machines;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
    }

    @Test
    void unchangedStatus_ProducesNoNewRevision() {
        AppUser alice = saveUser("connectivity@example.com");

        Machine machine = CurrentUser.runWhere(AuthContext.ui(alice.getId(), alice.getEmail()),
                () -> machineService.register(new RegisterMachineInput("connectivity-host", 22, "root")));
        // Register is the only config edit → exactly one revision.
        assertThat(revisionsOf(machine.getId())).hasSize(1);

        // Probe reports ONLINE; current status is UNKNOWN → a real change is written.
        runJob(new ProbeStub(machine.getId(), MachineStatus.ONLINE));
        assertThat(revisionsOf(machine.getId())).hasSize(2);
        assertThat(machines.findById(machine.getId()).orElseThrow().getStatus())
                .isEqualTo(MachineStatus.ONLINE);

        // Probe reports ONLINE again; status is unchanged → NO new revision.
        runJob(new ProbeStub(machine.getId(), MachineStatus.ONLINE));
        assertThat(revisionsOf(machine.getId())).hasSize(2);
    }

    /** Runs one connectivity cycle in a committing, system-scoped transaction. */
    private void runJob(SshExecutor ssh) {
        ConnectivityCheckJob job = new ConnectivityCheckJob(machines, ssh);
        CurrentUser.runWhere(AuthContext.system(), () -> tx.execute(status -> {
            job.checkAll();
            return null;
        }));
    }

    private AppUser saveUser(String email) {
        AppUser user = new AppUser();
        user.setEmail(email);
        user.setGoogleSub("dev|" + email);
        user.setName(email.substring(0, email.indexOf('@')));
        return users.save(user);
    }

    private List<Number> revisionsOf(String machineId) {
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            return AuditReaderFactory.get(em).getRevisions(Machine.class, machineId);
        } finally {
            em.close();
        }
    }

    /**
     * SSH stub that only ever runs against the machine under test: it reports the
     * given status (mapped back to an exit code) for that host and leaves any other
     * fleet machine untouched by reporting its current status.
     */
    private final class ProbeStub implements SshExecutor {

        private final String machineId;
        private final MachineStatus status;

        private ProbeStub(String machineId, MachineStatus status) {
            this.machineId = machineId;
            this.status = status;
        }

        @Override
        public ExecResult exec(SshTarget target, List<String> argv, boolean sudo) {
            Machine target1 = machines.findById(machineId).orElseThrow();
            boolean isUnderTest = target1.getHost().equals(target.host())
                    && target1.getPort() == target.port()
                    && target1.getLoginUser().equals(target.loginUser());
            MachineStatus effective = isUnderTest ? status : MachineStatus.ONLINE;
            return effective == MachineStatus.ONLINE
                    ? new ExecResult(0, "", "")
                    : new ExecResult(1, "", "");
        }

        @Override
        public void execStreaming(SshTarget target, List<String> argv, boolean sudo, OutputSink sink) {
            throw new UnsupportedOperationException("not used by the connectivity job");
        }
    }
}
