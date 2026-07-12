package com.iskeru.computeadmin.machine;

import com.iskeru.computeadmin.machine.service.MachineFacts;
import com.iskeru.computeadmin.machine.service.MachineFactsProbe;
import com.iskeru.computeadmin.ssh.ExecResult;
import com.iskeru.computeadmin.ssh.OutputSink;
import com.iskeru.computeadmin.ssh.SshExecutor;
import com.iskeru.computeadmin.ssh.SshTarget;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Read-only facts probe (spec-018): {@code /etc/os-release} {@code ID} maps to the OS
 * tag (quotes stripped, RHEL family normalised), DMI vendor files map to the cloud tag,
 * and absent/unrecognised signals yield {@code null} rather than an error. Plain unit
 * test over a stub {@link SshExecutor} keyed by the {@code cat <path>} argv.
 *
 * <p>spec-018.
 */
class MachineFactsProbeTest {

    private static final SshTarget TARGET = new SshTarget("host", 22, "root");

    /** A stub executor that returns canned stdout for {@code cat <path>}, else empty. */
    private static final class StubSsh implements SshExecutor {
        private final Map<String, String> files = new HashMap<>();

        StubSsh with(String path, String stdout) {
            files.put(path, stdout);
            return this;
        }

        @Override
        public ExecResult exec(SshTarget target, List<String> argv, boolean sudo) {
            if (argv.size() == 2 && "cat".equals(argv.get(0)) && files.containsKey(argv.get(1))) {
                return new ExecResult(0, files.get(argv.get(1)), "");
            }
            return new ExecResult(1, "", "no such file");
        }

        @Override
        public void execStreaming(SshTarget target, List<String> argv, boolean sudo, OutputSink sink) {
            sink.onComplete(0);
        }
    }

    @Test
    void mapsOsReleaseIdToOsTag() {
        MachineFacts facts = new MachineFactsProbe(new StubSsh().with("/etc/os-release",
                "NAME=\"Ubuntu\"\nID=ubuntu\nVERSION_ID=\"22.04\"\n")).probe(TARGET);
        assertThat(facts.os()).isEqualTo("ubuntu");
        assertThat(facts.cloud()).isNull();
    }

    @Test
    void stripsQuotesAndNormalisesRhelFamily() {
        MachineFacts facts = new MachineFactsProbe(new StubSsh().with("/etc/os-release",
                "NAME=\"CentOS Stream\"\nID=\"centos\"\n")).probe(TARGET);
        assertThat(facts.os()).isEqualTo("rhel");
    }

    @Test
    void detectsAwsFromDmiVendor() {
        MachineFacts facts = new MachineFactsProbe(new StubSsh()
                .with("/etc/os-release", "ID=debian\n")
                .with("/sys/class/dmi/id/sys_vendor", "Amazon EC2\n")).probe(TARGET);
        assertThat(facts.os()).isEqualTo("debian");
        assertThat(facts.cloud()).isEqualTo("aws");
    }

    @Test
    void unknownOsAndNoDmiYieldNulls() {
        MachineFacts facts = new MachineFactsProbe(new StubSsh().with("/etc/os-release",
                "ID=plan9\n")).probe(TARGET);
        assertThat(facts.os()).isNull();
        assertThat(facts.cloud()).isNull();
    }
}
