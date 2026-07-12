package com.iskeru.computeadmin.discovery.service;

import com.iskeru.computeadmin.discovery.ProposedAction;
import com.iskeru.computeadmin.discovery.ProposedRecipe;
import com.iskeru.computeadmin.discovery.RecipeDiscoverer;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.recipe.model.RecipeType;
import com.iskeru.computeadmin.recipe.service.ParamBinder;
import com.iskeru.computeadmin.ssh.SshExecutor;
import com.iskeru.computeadmin.ssh.SshTarget;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static com.iskeru.computeadmin.discovery.Proposals.allowedSet;
import static com.iskeru.computeadmin.discovery.Proposals.intRange;
import static com.iskeru.computeadmin.discovery.Proposals.literal;
import static com.iskeru.computeadmin.discovery.Proposals.param;

/**
 * Discovers systemd services and proposes a curated unit-ops catalog — the
 * bare-systemd analogue of {@link DockerDiscoverer}, so a systemd app gets the same
 * lifecycle affordances a docker app already has. Probes (fixed, read-only):
 * {@code command -v systemctl} and
 * {@code systemctl list-units --type=service --state=running --no-legend}, taking the
 * first field of each line as the unit name.
 *
 * <p>Actions proposed (all land {@code PENDING_APPROVAL}, gated exactly like docker's)
 * are keyed by the <strong>reserved {@code app-name} param</strong> (spec-026): the
 * discovered unit <em>is</em> the app, so the same {@code app-name} that correlates a
 * monitor to its app also correlates these ops to it. <b>status</b>
 * ({@code systemctl status}, read-only), <b>restart</b> ({@code systemctl restart},
 * {@code sudo -n}, S5), and <b>tail-logs</b> ({@code journalctl -u … -n}, with a
 * bounded {@code lines} {@code INT_RANGE}, one-shot). The {@code app-name} param is a
 * closed {@code ALLOWED_SET} of the discovered units (attacker-influenced, S3 — the 004
 * human approval is the mitigation). No mutating command is ever probed; this discoverer
 * only proposes.
 *
 * <p>spec-026.
 */
@Component
public class SystemdDiscoverer implements RecipeDiscoverer {

    private static final int MAX_TAIL = 10_000;
    private static final Pattern APP_NAME = Pattern.compile(ParamBinder.APP_NAME_PATTERN);

    @Override
    public List<ProposedRecipe> discover(Machine machine, SshExecutor ssh) {
        SshTarget target = Probes.target(machine);
        if (!Probes.commandExists(ssh, target, "systemctl")) {
            return List.of();
        }
        List<String> units = new ArrayList<>();
        for (String line : Probes.lines(ssh, target,
                List.of("systemctl", "list-units", "--type=service", "--state=running", "--no-legend"))) {
            // The unit name is the first whitespace-delimited field of each row.
            String unit = line.split("\\s+", 2)[0];
            if (APP_NAME.matcher(unit).matches()) {
                units.add(unit);
            }
        }

        if (units.isEmpty()) {
            // systemctl is present but no running unit is a candidate app — nothing to
            // propose (unlike docker's param-free `ps`, systemd ops all need a unit).
            return List.of();
        }
        List<ProposedAction> actions = new ArrayList<>();
        {
            actions.add(new ProposedAction("status",
                    "Show a unit's status (systemctl status). Read-only.", false,
                    List.of(literal("systemctl"), literal("status"), param(ParamBinder.APP_NAME_COMPONENT)),
                    List.of(allowedSet(ParamBinder.APP_NAME_COMPONENT, units))));
            actions.add(new ProposedAction("restart",
                    "Restart a systemd unit (systemctl restart).", true,
                    List.of(literal("systemctl"), literal("restart"), param(ParamBinder.APP_NAME_COMPONENT)),
                    List.of(allowedSet(ParamBinder.APP_NAME_COMPONENT, units))));
            actions.add(new ProposedAction("tail-logs",
                    "Show the last N journal lines for a unit (journalctl -u … -n).", false,
                    List.of(literal("journalctl"), literal("-u"), param(ParamBinder.APP_NAME_COMPONENT),
                            literal("-n"), param("lines")),
                    List.of(allowedSet(ParamBinder.APP_NAME_COMPONENT, units),
                            intRange("lines", 1, MAX_TAIL))));
        }
        return List.of(new ProposedRecipe(RecipeType.SYSTEMD, "systemd",
                "Discovered systemd unit operations.", actions));
    }
}
