package com.iskeru.computeadmin.discovery.service;

import com.iskeru.computeadmin.discovery.ProposedAction;
import com.iskeru.computeadmin.discovery.ProposedRecipe;
import com.iskeru.computeadmin.discovery.RecipeDiscoverer;
import com.iskeru.computeadmin.discovery.model.DiscovererFamily;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.recipe.model.RecipeType;
import com.iskeru.computeadmin.ssh.SshExecutor;
import com.iskeru.computeadmin.ssh.SshTarget;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.iskeru.computeadmin.discovery.Proposals.literal;

/**
 * Discovers cron and proposes a read-only listing. Probes (fixed, read-only):
 * {@code command -v crontab}, {@code crontab -l} (the login user's table), and
 * {@code ls /etc/cron.d}.
 *
 * <p>Action proposed (lands {@code PENDING_APPROVAL}): <b>list</b>
 * ({@code crontab -l}). Adding/removing cron entries is the deferred "broad" scope
 * and deliberately out of v1 (spec 006).
 *
 * <p>spec-006.
 */
@Component
public class CronDiscoverer implements RecipeDiscoverer {

    @Override
    public DiscovererFamily family() {
        return DiscovererFamily.CRON;
    }

    @Override
    public List<ProposedRecipe> discover(Machine machine, SshExecutor ssh) {
        SshTarget target = Probes.target(machine);
        if (!Probes.commandExists(ssh, target, "crontab")) {
            return List.of();
        }
        // Read-only probes: the login user's crontab and the system cron.d drop-ins.
        ssh.exec(target, List.of("crontab", "-l"), false);
        ssh.exec(target, List.of("ls", "/etc/cron.d"), false);

        ProposedAction list = new ProposedAction("list",
                "List the login user's crontab (crontab -l). Read-only.", false,
                List.of(literal("crontab"), literal("-l")), List.of());
        return List.of(new ProposedRecipe(RecipeType.CRON, "cron",
                "Discovered cron listing.", List.of(list)));
    }
}
