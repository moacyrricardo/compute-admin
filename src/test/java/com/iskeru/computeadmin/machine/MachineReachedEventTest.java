package com.iskeru.computeadmin.machine;

import com.iskeru.computeadmin.audit.AuditRevision;
import com.iskeru.computeadmin.auth.api.AuthDtos;
import com.iskeru.computeadmin.auth.model.AppUser;
import com.iskeru.computeadmin.auth.repository.AppUserRepository;
import com.iskeru.computeadmin.common.AuthContext;
import com.iskeru.computeadmin.common.CurrentUser;
import com.iskeru.computeadmin.common.Via;
import com.iskeru.computeadmin.machine.api.MachineDtos;
import com.iskeru.computeadmin.machine.event.MachineReachedEvent;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.machine.model.MachineStatus;
import com.iskeru.computeadmin.machine.repository.MachineRepository;
import com.iskeru.computeadmin.ssh.ExecResult;
import com.iskeru.computeadmin.ssh.OutputSink;
import com.iskeru.computeadmin.ssh.SshExecutor;
import com.iskeru.computeadmin.ssh.SshTarget;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.envers.AuditReaderFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Event-driven connectivity status (spec-019): publishing a {@link MachineReachedEvent}
 * refreshes the machine to {@code ONLINE} through the async
 * {@link com.iskeru.computeadmin.machine.event.MachineStatusUpdater}, stamped
 * {@code via = SYSTEM} (unbound pool thread); an already-{@code ONLINE} machine
 * yields no new Envers revision (idempotence — a liveness signal is not a config
 * edit); and {@code POST /api/machines/{id}/test} is owner-scoped (404 cross-user)
 * and converges the durable status to {@code ONLINE}.
 *
 * <p>A full {@code @SpringBootTest} so the real {@code @EnableAsync} +
 * {@code @TransactionalEventListener} pipeline and Envers run end-to-end. The
 * {@code machineEventExecutor} pool is single-threaded, so submitting a barrier task
 * and waiting on it ({@link #drainEvents()}) deterministically proves the listener
 * task enqueued during {@code publishEvent} has already run — no sleeps.
 *
 * <p>Shared in-memory DB discipline (specs 013/016): unique owners/hosts per test and
 * an {@code @AfterEach} that deletes every committed row this class created.
 *
 * <p>spec-019.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MachineReachedEventTest {

    @TestConfiguration
    static class FakeSshConfig {

        @Bean
        @Primary
        SshExecutor fakeSshExecutor() {
            return new SshExecutor() {
                @Override
                public ExecResult exec(SshTarget target, List<String> argv, boolean sudo) {
                    return new ExecResult(0, "", "");
                }

                @Override
                public void execStreaming(SshTarget target, List<String> argv, boolean sudo, OutputSink sink) {
                    sink.onComplete(0);
                }
            };
        }
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private MachineRepository machines;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    @Qualifier("machineEventExecutor")
    private AsyncTaskExecutor eventExecutor;

    private TransactionTemplate tx;

    private final List<String> createdMachineIds = new ArrayList<>();
    private final List<String> createdUserIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void cleanUp() {
        tx.executeWithoutResult(status -> {
            createdMachineIds.forEach(machines::deleteById);
            createdUserIds.forEach(users::deleteById);
        });
        createdMachineIds.clear();
        createdUserIds.clear();
    }

    @Test
    void publishingMachineReachedEvent_FlipsStaleUnreachableToOnline_StampedSystem() throws Exception {
        AppUser owner = saveUser("event-flip-" + UUID.randomUUID() + "@example.com");
        Machine machine = createMachine(owner, "flip-" + UUID.randomUUID(), MachineStatus.UNREACHABLE);

        // The one config write (the insert) is the only revision so far, and it is
        // attributed to the acting UI user.
        List<Number> before = revisionsOf(machine.getId());
        assertThat(before).hasSize(1);
        assertThat(findRevision(before.get(before.size() - 1)).getVia()).isEqualTo(Via.UI);

        events.publishEvent(new MachineReachedEvent(machine.getId(), Instant.now()));
        drainEvents();

        assertThat(machines.findById(machine.getId()).orElseThrow().getStatus())
                .isEqualTo(MachineStatus.ONLINE);

        List<Number> after = revisionsOf(machine.getId());
        assertThat(after).hasSize(2);
        AuditRevision eventRevision = findRevision(after.get(after.size() - 1));
        assertThat(eventRevision.getVia()).isEqualTo(Via.SYSTEM);
        assertThat(eventRevision.getUserId()).isNull();
    }

    @Test
    void publishingMachineReachedEvent_ForAlreadyOnlineMachine_AddsNoRevision() throws Exception {
        AppUser owner = saveUser("event-idem-" + UUID.randomUUID() + "@example.com");
        Machine machine = createMachine(owner, "idem-" + UUID.randomUUID(), MachineStatus.ONLINE);

        int before = revisionsOf(machine.getId()).size();
        assertThat(before).isEqualTo(1);

        events.publishEvent(new MachineReachedEvent(machine.getId(), Instant.now()));
        drainEvents();

        // Write-on-change: already ONLINE, so no mutation and no new machine_aud row.
        assertThat(machines.findById(machine.getId()).orElseThrow().getStatus())
                .isEqualTo(MachineStatus.ONLINE);
        assertThat(revisionsOf(machine.getId())).hasSize(before);
    }

    @Test
    void testConnection_OnAnotherUsersMachine_Is404() {
        AuthDtos.Session a = login("test-a-" + UUID.randomUUID() + "@example.com");
        AuthDtos.Session b = login("test-b-" + UUID.randomUUID() + "@example.com");

        MachineDtos.MachineView aMachine = rest.postForObject(
                "/api/machines",
                new HttpEntity<>(new MachineDtos.RegisterMachineRequest(
                        "owned-" + UUID.randomUUID(), 
                        "owned-" + UUID.randomUUID(), 22, "root"), bearer(a.token())),
                MachineDtos.MachineView.class);
        rememberMachine(aMachine.id());

        ResponseEntity<String> bTest = rest.exchange(
                "/api/machines/" + aMachine.id() + "/test", HttpMethod.POST,
                new HttpEntity<>(bearer(b.token())), String.class);
        assertThat(bTest.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void testConnection_ProbesNowAndRefreshesStatusToOnline() throws Exception {
        AuthDtos.Session owner = login("test-refresh-" + UUID.randomUUID() + "@example.com");

        MachineDtos.MachineView created = rest.postForObject(
                "/api/machines",
                new HttpEntity<>(new MachineDtos.RegisterMachineRequest(
                        "refresh-" + UUID.randomUUID(), 
                        "refresh-" + UUID.randomUUID(), 22, "root"), bearer(owner.token())),
                MachineDtos.MachineView.class);
        rememberMachine(created.id());
        assertThat(created.status()).isEqualTo(MachineStatus.UNKNOWN);

        // The endpoint reports the freshly observed status immediately (fake probe = ONLINE).
        MachineDtos.MachineView tested = rest.postForObject(
                "/api/machines/" + created.id() + "/test",
                new HttpEntity<>(bearer(owner.token())), MachineDtos.MachineView.class);
        assertThat(tested.status()).isEqualTo(MachineStatus.ONLINE);

        // The durable status converges once the async listener commits.
        drainEvents();
        ResponseEntity<MachineDtos.MachineView> reloaded = rest.exchange(
                "/api/machines/" + created.id(), HttpMethod.GET,
                new HttpEntity<>(bearer(owner.token())), MachineDtos.MachineView.class);
        assertThat(reloaded.getBody().status()).isEqualTo(MachineStatus.ONLINE);
    }

    // --- helpers --------------------------------------------------------------

    /** Barrier on the single-threaded event pool: after this returns, every listener
     *  task enqueued during a preceding {@code publishEvent} has completed. */
    private void drainEvents() throws Exception {
        eventExecutor.submit(() -> null).get(5, TimeUnit.SECONDS);
    }

    private Machine createMachine(AppUser owner, String host, MachineStatus status) {
        Machine saved = CurrentUser.runWhere(AuthContext.ui(owner.getId(), owner.getEmail()),
                () -> tx.execute(s -> {
                    AppUser managed = users.findById(owner.getId()).orElseThrow();
                    Machine machine = new Machine();
                    machine.setOwner(managed);
                    machine.setName(host);
                    machine.setHost(host);
                    machine.setPort(22);
                    machine.setLoginUser("root");
                    machine.setStatus(status);
                    return machines.save(machine);
                }));
        rememberMachine(saved.getId());
        return saved;
    }

    private AppUser saveUser(String email) {
        AppUser user = new AppUser();
        user.setEmail(email);
        user.setPasswordHash("$2a$10$fixturehashfixturehashfixturehashfixturehashfixT");
        user.setName(email.substring(0, email.indexOf('@')));
        AppUser saved = tx.execute(s -> users.save(user));
        createdUserIds.add(saved.getId());
        return saved;
    }

    private AuthDtos.Session login(String email) {
        ResponseEntity<AuthDtos.Session> reg = rest.postForEntity(
                "/api/auth/register",
                new AuthDtos.RegisterRequest(email, "password-123", null), AuthDtos.Session.class);
        AuthDtos.Session session = reg.getStatusCode().is2xxSuccessful() && reg.getBody() != null
                ? reg.getBody()
                : rest.postForObject("/api/auth/login",
                        new AuthDtos.LoginRequest(email, "password-123"), AuthDtos.Session.class);
        users.findByEmail(email).map(AppUser::getId).ifPresent(id -> {
            if (!createdUserIds.contains(id)) {
                createdUserIds.add(id);
            }
        });
        return session;
    }

    private void rememberMachine(String id) {
        if (id != null && !createdMachineIds.contains(id)) {
            createdMachineIds.add(id);
        }
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

    private static HttpHeaders bearer(String jwt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);
        return headers;
    }
}
