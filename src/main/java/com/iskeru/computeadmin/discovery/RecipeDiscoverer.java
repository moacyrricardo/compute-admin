package com.iskeru.computeadmin.discovery;

import com.iskeru.computeadmin.discovery.model.DiscovererFamily;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.ssh.SshExecutor;

import java.util.List;

/**
 * Port for one built-in service type's recipe discovery: SSH into a known machine,
 * run a small set of <strong>fixed, source-controlled, read-only</strong> probes,
 * and <strong>propose</strong> a curated recipe + action catalog. It never mutates
 * the box (probes are read-only) and never approves anything — the proposals are
 * handed back for {@link DiscoveryService} to persist in {@code PENDING_APPROVAL}
 * through the 004 gate.
 *
 * <p>Probe commands are constants in each implementation, never agent-supplied:
 * discovery runs un-gated commands (the deliberate exception to "only APPROVED
 * actions run", spec 006 Known Gaps), so the probe set must stay fixed and
 * read-only. The names/paths a probe returns are attacker-influenced input (S3);
 * they become {@code ALLOWED_SET} values on the proposed params, and the 004
 * human-approval step is the mitigation.
 *
 * <p>Business code depends on this port, never on a concrete discoverer. Spring
 * injects every implementation as a {@code List<RecipeDiscoverer>} into
 * {@link DiscoveryService}.
 *
 * <p><strong>Enablement (spec-035).</strong> Each discoverer belongs to a
 * {@link DiscovererFamily}; {@link DiscoveryService} probes it only when that family is
 * enabled for the machine (docker default-off, the rest default-on). A disabled family
 * is skipped entirely — never probed — so this is upstream of, and distinct from, the
 * approval gate.
 *
 * <p>spec-006; family enablement in spec-035.
 */
public interface RecipeDiscoverer {

    /**
     * Runs this discoverer's fixed read-only probes against {@code machine} over
     * {@code ssh} and returns the recipes it proposes — empty when the service is
     * not installed. Must never issue a mutating command.
     */
    List<ProposedRecipe> discover(Machine machine, SshExecutor ssh);

    /**
     * The enablement family this discoverer belongs to (spec-035). Discoverers in the
     * same family (e.g. the two docker discoverers) share one per-machine toggle.
     */
    DiscovererFamily family();
}
