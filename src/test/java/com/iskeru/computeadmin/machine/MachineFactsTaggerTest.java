package com.iskeru.computeadmin.machine;

import com.iskeru.computeadmin.auth.model.AppUser;
import com.iskeru.computeadmin.auth.repository.AppUserRepository;
import com.iskeru.computeadmin.common.AuthContext;
import com.iskeru.computeadmin.common.CurrentUser;
import com.iskeru.computeadmin.machine.event.MachineReachedEvent;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.machine.model.Tag;
import com.iskeru.computeadmin.machine.repository.MachineRepository;
import com.iskeru.computeadmin.machine.repository.TagRepository;
import com.iskeru.computeadmin.machine.service.MachineService;
import com.iskeru.computeadmin.machine.service.MachineService.RegisterMachineInput;
import com.iskeru.computeadmin.ssh.ExecResult;
import com.iskeru.computeadmin.ssh.OutputSink;
import com.iskeru.computeadmin.ssh.SshExecutor;
import com.iskeru.computeadmin.ssh.SshTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Auto-tagging on first reach (spec-018, source 2): a {@link MachineReachedEvent} drives
 * the async {@code MachineFactsTagger} → read-only {@code MachineFactsProbe} → add-only
 * detected tags. Proves the event wiring end to end, that add-only preserves manual and
 * provisional tags, and that the once-per-machine guard ({@code factsProbedAt}) keeps a
 * user-removed auto-tag from being re-added on a later reach.
 *
 * <p>Shared in-memory DB discipline (specs 013/016/019): unique owner/host per test and
 * an {@code @AfterEach} that removes every committed row this class created.
 *
 * <p>spec-018.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class MachineFactsTaggerTest {

    @TestConfiguration
    static class FakeSshConfig {
        @Bean
        @Primary
        SshExecutor fakeSshExecutor() {
            return new SshExecutor() {
                @Override
                public ExecResult exec(SshTarget target, List<String> argv, boolean sudo) {
                    if (argv.size() == 2 && "cat".equals(argv.get(0))) {
                        String path = argv.get(1);
                        if ("/etc/os-release".equals(path)) {
                            return new ExecResult(0, "ID=ubuntu\n", "");
                        }
                        if ("/sys/class/dmi/id/sys_vendor".equals(path)) {
                            return new ExecResult(0, "Amazon EC2\n", "");
                        }
                    }
                    return new ExecResult(1, "", "");
                }

                @Override
                public void execStreaming(SshTarget target, List<String> argv, boolean sudo, OutputSink sink) {
                    sink.onComplete(0);
                }
            };
        }
    }

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private MachineService machineService;

    @Autowired
    private MachineRepository machines;

    @Autowired
    private TagRepository tags;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    @Qualifier("machineEventExecutor")
    private AsyncTaskExecutor eventExecutor;

    private TransactionTemplate tx;
    private String machineId;
    private String ownerId;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void cleanUp() {
        tx.executeWithoutResult(s -> machines.findById(machineId).ifPresent(m -> m.getTags().clear()));
        tx.executeWithoutResult(s -> machines.deleteById(machineId));
        tx.executeWithoutResult(s -> {
            for (String name : List.of("ubuntu", "aws", "prod")) {
                tags.findByOwnerIdAndName(ownerId, name).ifPresent(tags::delete);
            }
        });
        tx.executeWithoutResult(s -> users.deleteById(ownerId));
    }

    @Test
    void firstReach_ProbesAddsDetectedTags_AddOnly_AndRunsOnce() throws Exception {
        AppUser owner = tx.execute(s -> {
            AppUser u = new AppUser();
            u.setEmail("facts-" + UUID.randomUUID() + "@example.com");
            u.setPasswordHash("$2a$10$fixturehashfixturehashfixturehashfixturehashfixT");
            u.setName("facts");
            return users.save(u);
        });
        ownerId = owner.getId();

        // Register with a login user that carries no provisional tag, then add a manual one.
        Machine machine = CurrentUser.runWhere(AuthContext.ui(owner.getId(), owner.getEmail()),
                () -> machineService.register(new RegisterMachineInput("box-" + UUID.randomUUID(), "box-" + UUID.randomUUID(), 22, "root")));
        machineId = machine.getId();
        CurrentUser.runWhere(AuthContext.ui(owner.getId(), owner.getEmail()),
                () -> machineService.tag(machineId, java.util.Set.of("prod")));

        // First reach: probe detects Ubuntu on AWS → tags added, manual tag preserved.
        events.publishEvent(new MachineReachedEvent(machineId, Instant.now()));
        drainEvents();

        assertThat(tagNames(machineId)).containsExactlyInAnyOrder("prod", "ubuntu", "aws");
        assertThat(machines.findById(machineId).orElseThrow().getFactsProbedAt()).isNotNull();

        // The user removes an auto-tag; a later reach must not re-add it (once-per-machine).
        CurrentUser.runWhere(AuthContext.ui(owner.getId(), owner.getEmail()),
                () -> machineService.untag(machineId, "ubuntu"));
        events.publishEvent(new MachineReachedEvent(machineId, Instant.now()));
        drainEvents();

        assertThat(tagNames(machineId)).containsExactlyInAnyOrder("prod", "aws");
    }

    /** Barrier on the single-threaded event pool: after this returns, every listener
     *  task enqueued during a preceding {@code publishEvent} has completed. */
    private void drainEvents() throws Exception {
        eventExecutor.submit(() -> null).get(5, TimeUnit.SECONDS);
    }

    private List<String> tagNames(String id) {
        return tx.execute(s -> machines.findById(id).orElseThrow()
                .getTags().stream().map(Tag::getName).sorted().toList());
    }
}
