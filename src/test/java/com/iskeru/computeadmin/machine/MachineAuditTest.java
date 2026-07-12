package com.iskeru.computeadmin.machine;

import com.iskeru.computeadmin.audit.AuditRevision;
import com.iskeru.computeadmin.auth.model.AppUser;
import com.iskeru.computeadmin.auth.repository.AppUserRepository;
import com.iskeru.computeadmin.common.AuthContext;
import com.iskeru.computeadmin.common.CurrentUser;
import com.iskeru.computeadmin.common.Via;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.machine.model.MachineStatus;
import com.iskeru.computeadmin.machine.repository.MachineRepository;
import com.iskeru.computeadmin.machine.service.MachineService;
import com.iskeru.computeadmin.machine.service.MachineService.RegisterMachineInput;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.envers.AuditReader;
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

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end audit foundation (spec-003): a {@code Machine} write opens a
 * {@code machine_aud} revision whose {@code revinfo} row is stamped by
 * {@link com.iskeru.computeadmin.audit.CurrentUserRevisionListener} with the
 * ambient actor — the acting {@code user_id} and {@code via = UI} for a
 * UI-scoped write, and {@code user_id = null} with {@code via = SYSTEM} for an
 * unattended system-scoped write (as the connectivity job does).
 *
 * <p>Envers persists audit rows at transaction commit, so these tests opt out of
 * the default rollback wrapper ({@code NOT_SUPPORTED}) and let each write commit
 * in its own transaction, then read the revision back via the Envers
 * {@link AuditReader}.
 *
 * <p>spec-003.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(MachineService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MachineAuditTest {

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
    void uiScopedMachineWrite_StampsRevisionWithUserAndUi() {
        AppUser alice = saveUser("audit-ui@example.com");

        Machine machine = CurrentUser.runWhere(AuthContext.ui(alice.getId(), alice.getEmail()),
                () -> machineService.register(new RegisterMachineInput("audit-ui-host", 22, "root")));

        List<Number> revisions = revisionsOf(machine.getId());
        assertThat(revisions).hasSize(1);

        AuditRevision revision = findRevision(revisions.get(0));
        assertThat(revision.getVia()).isEqualTo(Via.UI);
        assertThat(revision.getUserId()).isEqualTo(alice.getId());
    }

    @Test
    void systemScopedMachineWrite_StampsRevisionWithNullUserAndSystem() {
        AppUser alice = saveUser("audit-system@example.com");

        Machine machine = CurrentUser.runWhere(AuthContext.ui(alice.getId(), alice.getEmail()),
                () -> machineService.register(new RegisterMachineInput("audit-system-host", 22, "root")));

        // An unattended, system-scoped status update — what the connectivity job does.
        CurrentUser.runWhere(AuthContext.system(), () -> tx.execute(status -> {
            Machine loaded = machines.findById(machine.getId()).orElseThrow();
            loaded.setStatus(MachineStatus.ONLINE);
            loaded.setUpdatedAt(Instant.now());
            return machines.save(loaded);
        }));

        List<Number> revisions = revisionsOf(machine.getId());
        assertThat(revisions).hasSize(2);

        AuditRevision uiRevision = findRevision(revisions.get(0));
        assertThat(uiRevision.getVia()).isEqualTo(Via.UI);
        assertThat(uiRevision.getUserId()).isEqualTo(alice.getId());

        AuditRevision systemRevision = findRevision(revisions.get(1));
        assertThat(systemRevision.getVia()).isEqualTo(Via.SYSTEM);
        assertThat(systemRevision.getUserId()).isNull();
    }

    private AppUser saveUser(String email) {
        AppUser user = new AppUser();
        user.setEmail(email);
        user.setPasswordHash("$2a$10$fixturehashfixturehashfixturehashfixturehashfixT");
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

    private AuditRevision findRevision(Number revision) {
        EntityManager em = entityManagerFactory.createEntityManager();
        try {
            return AuditReaderFactory.get(em).findRevision(AuditRevision.class, revision);
        } finally {
            em.close();
        }
    }
}
