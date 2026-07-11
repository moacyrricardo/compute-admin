package com.iskeru.computeadmin.discovery.service;

import com.iskeru.computeadmin.discovery.ProposedAction;
import com.iskeru.computeadmin.discovery.ProposedRecipe;
import com.iskeru.computeadmin.discovery.RecipeDiscoverer;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.recipe.model.RecipeType;
import com.iskeru.computeadmin.ssh.SshExecutor;
import com.iskeru.computeadmin.ssh.SshTarget;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static com.iskeru.computeadmin.discovery.Proposals.allowedSet;
import static com.iskeru.computeadmin.discovery.Proposals.literal;
import static com.iskeru.computeadmin.discovery.Proposals.param;

/**
 * Discovers an nginx install and proposes a curated ops catalog. Probes (fixed,
 * read-only): {@code command -v nginx}, {@code nginx -t}, and {@code ls} of
 * {@code sites-available}/{@code sites-enabled}.
 *
 * <p>Actions proposed (all land {@code PENDING_APPROVAL}): <b>test-config</b>
 * ({@code nginx -t}), <b>reload</b>/<b>restart</b> (sudo {@code systemctl}), and —
 * only when sites were discovered — <b>enable-site</b>/<b>disable-site</b> (sudo).
 * Because argv elements are discrete (never concatenated, S4), the {@code site}
 * param is a closed set of the <em>full discovered paths</em>: {@code enable-site}
 * symlinks one of {@code /etc/nginx/sites-available/*} into the enabled dir, and
 * {@code disable-site} removes one of {@code /etc/nginx/sites-enabled/*}. The set
 * values come straight from probe output (attacker-influenced, S3 — the human
 * approval step is the mitigation).
 *
 * <p>spec-006.
 */
@Component
public class NginxDiscoverer implements RecipeDiscoverer {

    private static final String AVAILABLE_DIR = "/etc/nginx/sites-available";
    private static final String ENABLED_DIR = "/etc/nginx/sites-enabled";

    @Override
    public List<ProposedRecipe> discover(Machine machine, SshExecutor ssh) {
        SshTarget target = Probes.target(machine);
        if (!Probes.commandExists(ssh, target, "nginx")) {
            return List.of();
        }
        // Read-only config-test probe (also surfaced below as the test-config action).
        ssh.exec(target, List.of("nginx", "-t"), false);
        List<String> available = Probes.lines(ssh, target, List.of("ls", AVAILABLE_DIR));
        List<String> enabled = Probes.lines(ssh, target, List.of("ls", ENABLED_DIR));

        List<ProposedAction> actions = new ArrayList<>();
        actions.add(new ProposedAction("test-config",
                "Validate the nginx configuration (nginx -t). Read-only.", false,
                List.of(literal("nginx"), literal("-t")), List.of()));
        actions.add(new ProposedAction("reload",
                "Gracefully reload nginx (systemctl reload nginx).", true,
                List.of(literal("systemctl"), literal("reload"), literal("nginx")), List.of()));
        actions.add(new ProposedAction("restart",
                "Restart nginx (systemctl restart nginx).", true,
                List.of(literal("systemctl"), literal("restart"), literal("nginx")), List.of()));

        if (!available.isEmpty()) {
            List<String> paths = available.stream().map(s -> AVAILABLE_DIR + "/" + s).toList();
            actions.add(new ProposedAction("enable-site",
                    "Symlink a site from sites-available into sites-enabled.", true,
                    List.of(literal("ln"), literal("-s"), param("site"), literal(ENABLED_DIR + "/")),
                    List.of(allowedSet("site", paths))));
        }
        if (!enabled.isEmpty()) {
            List<String> paths = enabled.stream().map(s -> ENABLED_DIR + "/" + s).toList();
            actions.add(new ProposedAction("disable-site",
                    "Remove a site symlink from sites-enabled.", true,
                    List.of(literal("rm"), param("site")),
                    List.of(allowedSet("site", paths))));
        }
        return List.of(new ProposedRecipe(RecipeType.NGINX, "nginx",
                "Discovered nginx service operations.", actions));
    }
}
