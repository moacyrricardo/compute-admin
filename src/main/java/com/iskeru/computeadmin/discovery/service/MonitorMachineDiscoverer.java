package com.iskeru.computeadmin.discovery.service;

import com.iskeru.computeadmin.discovery.ProposedAction;
import com.iskeru.computeadmin.discovery.ProposedRecipe;
import com.iskeru.computeadmin.discovery.RecipeDiscoverer;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.recipe.model.RecipeType;
import com.iskeru.computeadmin.ssh.SshExecutor;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.iskeru.computeadmin.discovery.Proposals.literal;

/**
 * Proposes the universal host-vitals recipe {@code monitor machine}: a single
 * {@link RecipeType#MONITOR}-typed recipe with four read-only, param-free actions —
 * <b>cpu</b> ({@code top -bn1}), <b>memory</b> ({@code free -m}, physical + swap),
 * <b>disk</b> ({@code df -h}), and <b>cores</b> ({@code nproc}, the docker CPU-axis
 * denominator, spec-037). It is the host-level counterpart to the app-monitor
 * family: a {@code MONITOR} action <strong>without</strong> an {@code APP_PORT_LIST}
 * param, which is how the dashboard (spec-024) routes it to the host panel rather than
 * an app card.
 *
 * <p>Unlike the service-gated discoverers ({@link DockerDiscoverer} et al.), this one
 * is <strong>universal</strong>: it runs <em>no</em> {@code command -v} probe and
 * always proposes the recipe, because every reachable Linux box has these tools (or a
 * documented {@code /proc} fallback). Like every discoverer it never mutates the box
 * (the proposed argv are read-only) and never approves — all three actions land
 * {@code PENDING_APPROVAL} through the {@link DiscoveryService} persist path and are
 * approved-once like any action (no read-only carve-out, MONITOR is display metadata
 * only). Re-discovery reconciles the one {@code (machine, MONITOR, "monitor machine")}
 * recipe in place (spec-021) rather than duplicating the host panel.
 *
 * <p>The actions return the tools' raw stdout; parsing {@code top}/{@code free}/
 * {@code df} into the host-panel bars is the browser's job (spec-024) — no server-side
 * sampler and no parsed-value storage (v1 has no time-series). Each argv is a single
 * fixed template with a documented fallback ({@code uptime} / {@code /proc/stat} for
 * cpu, {@code /proc/meminfo} for memory) left for a future per-OS refinement.
 *
 * <p>spec-023.
 */
@Component
public class MonitorMachineDiscoverer implements RecipeDiscoverer {

    @Override
    public List<ProposedRecipe> discover(Machine machine, SshExecutor ssh) {
        List<ProposedAction> actions = List.of(
                new ProposedAction("cpu",
                        "CPU load and utilisation snapshot (top -bn1). Read-only.", false,
                        List.of(literal("top"), literal("-bn1")), List.of()),
                new ProposedAction("memory",
                        "Physical memory and swap usage in MiB (free -m). Read-only.", false,
                        List.of(literal("free"), literal("-m")), List.of()),
                new ProposedAction("disk",
                        "Filesystem usage, human-readable (df -h). Read-only.", false,
                        List.of(literal("df"), literal("-h")), List.of()),
                // spec-037: the logical CPU count, the denominator for the docker
                // consumer CPU axis (docker stats CPUPerc sums cores, so % of host =
                // sum ÷ nproc). Named "cores" — not "cpu"/"load" — so the client's
                // metricKind() never confuses it with the top -bn1 host-CPU action.
                new ProposedAction("cores",
                        "Logical CPU count (nproc). Read-only.", false,
                        List.of(literal("nproc")), List.of()));
        return List.of(new ProposedRecipe(RecipeType.MONITOR, "monitor machine",
                "Universal host vitals: CPU, memory + swap, and disk usage.", actions));
    }
}
