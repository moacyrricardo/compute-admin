package com.iskeru.computeadmin.machine.service;

import com.iskeru.computeadmin.ssh.ExecResult;
import com.iskeru.computeadmin.ssh.SshExecutor;
import com.iskeru.computeadmin.ssh.SshSession;
import com.iskeru.computeadmin.ssh.SshTarget;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Read-only OS/cloud fact detector run over the {@link SshExecutor} port on the first
 * successful reach (spec-018, source 2). It only ever <em>reads</em> the box — {@code cat}
 * of {@code /etc/os-release} and the {@code /sys/class/dmi/id/*} DMI vendor files, no
 * sudo — honouring discovery's "never mutate the box" rule (spec-006). It returns the
 * detected {@link MachineFacts}; the caller ({@code MachineFactsTagger} → {@link
 * MachineService#applyDetectedFacts}) turns them into add-only tags.
 *
 * <p>Best-effort by design: any signal that is missing, unreadable, or unrecognised
 * yields {@code null} for that facet rather than an error, and the cloud heuristic is
 * DMI-based (not an authoritative cloud API — that is the parked spec-009 territory).
 *
 * <p>spec-018.
 */
@Service
public class MachineFactsProbe {

    /**
     * {@code /etc/os-release} {@code ID} → OS tag. RHEL-family IDs normalise to
     * {@code rhel}; unknown IDs yield no tag. Small and grows by need.
     */
    private static final Map<String, String> OS_TAGS = Map.of(
            "ubuntu", "ubuntu",
            "debian", "debian",
            "alpine", "alpine",
            "rhel", "rhel",
            "centos", "rhel",
            "rocky", "rhel",
            "almalinux", "rhel",
            "fedora", "rhel");

    private final SshExecutor ssh;

    public MachineFactsProbe(SshExecutor ssh) {
        this.ssh = ssh;
    }

    /**
     * Runs the read-only probes and returns what was detected (facets may be null). All
     * the {@code cat} reads share <strong>one</strong> authenticated session (spec-070):
     * one facts pass = one SSH handshake, not one per file. Best-effort — a failure to
     * even open the session yields all-null facts rather than an error.
     */
    public MachineFacts probe(SshTarget target) {
        try {
            return ssh.withSession(target, session ->
                    new MachineFacts(detectOs(session), detectCloud(session)));
        } catch (RuntimeException e) {
            return new MachineFacts(null, null);
        }
    }

    private String detectOs(SshSession session) {
        for (String line : read(session, "/etc/os-release")) {
            if (line.startsWith("ID=")) {
                String id = unquote(line.substring("ID=".length())).toLowerCase(Locale.ROOT);
                return OS_TAGS.get(id);
            }
        }
        return null;
    }

    private String detectCloud(SshSession session) {
        StringBuilder vendor = new StringBuilder();
        for (String path : List.of(
                "/sys/class/dmi/id/sys_vendor",
                "/sys/class/dmi/id/product_name",
                "/sys/class/dmi/id/board_vendor")) {
            for (String line : read(session, path)) {
                vendor.append(line).append('\n');
            }
        }
        String dmi = vendor.toString().toLowerCase(Locale.ROOT);
        if (dmi.contains("amazon") || dmi.contains("ec2")) {
            return "aws";
        }
        if (dmi.contains("google")) {
            return "gcp";
        }
        if (dmi.contains("microsoft")) {
            return "azure";
        }
        return null;
    }

    /** Trimmed, non-blank stdout lines of {@code cat path}; empty on any failure. */
    private List<String> read(SshSession session, String path) {
        try {
            ExecResult result = session.exec(List.of("cat", path), false);
            if (!result.succeeded() || result.stdout() == null) {
                return List.of();
            }
            return result.stdout().lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static String unquote(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2
                && (trimmed.charAt(0) == '"' || trimmed.charAt(0) == '\'')
                && trimmed.charAt(trimmed.length() - 1) == trimmed.charAt(0)) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }
}
