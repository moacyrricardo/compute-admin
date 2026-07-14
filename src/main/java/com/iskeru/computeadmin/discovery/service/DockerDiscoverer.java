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

import java.util.ArrayList;
import java.util.List;

import static com.iskeru.computeadmin.discovery.Proposals.allowedSet;
import static com.iskeru.computeadmin.discovery.Proposals.intRange;
import static com.iskeru.computeadmin.discovery.Proposals.literal;
import static com.iskeru.computeadmin.discovery.Proposals.param;

/**
 * Discovers a Docker install and proposes a curated container ops catalog. Probes
 * (fixed, read-only): {@code command -v docker} and
 * {@code docker ps --format '{{.Names}}'}.
 *
 * <p>Actions proposed (all land {@code PENDING_APPROVAL}): <b>ps</b> (read-only),
 * and — only when running containers were discovered — <b>restart</b>/<b>stop</b>/
 * <b>start container</b> and <b>logs</b>. The {@code container} param is a closed
 * set of the discovered names (attacker-influenced, S3); {@code logs} additionally
 * bounds {@code --tail} to an integer range.
 *
 * <p>spec-006.
 */
@Component
public class DockerDiscoverer implements RecipeDiscoverer {

    @Override
    public DiscovererFamily family() {
        return DiscovererFamily.DOCKER;
    }

    private static final int MAX_TAIL = 10_000;

    @Override
    public List<ProposedRecipe> discover(Machine machine, SshExecutor ssh) {
        SshTarget target = Probes.target(machine);
        if (!Probes.commandExists(ssh, target, "docker")) {
            return List.of();
        }
        List<String> containers = Probes.lines(ssh, target,
                List.of("docker", "ps", "--format", "{{.Names}}"));

        List<ProposedAction> actions = new ArrayList<>();
        actions.add(new ProposedAction("ps",
                "List running containers (docker ps). Read-only.", false,
                List.of(literal("docker"), literal("ps")), List.of()));

        if (!containers.isEmpty()) {
            actions.add(new ProposedAction("restart container",
                    "Restart a running container.", false,
                    List.of(literal("docker"), literal("restart"), param("container")),
                    List.of(allowedSet("container", containers))));
            actions.add(new ProposedAction("stop container",
                    "Stop a running container.", false,
                    List.of(literal("docker"), literal("stop"), param("container")),
                    List.of(allowedSet("container", containers))));
            actions.add(new ProposedAction("start container",
                    "Start a container.", false,
                    List.of(literal("docker"), literal("start"), param("container")),
                    List.of(allowedSet("container", containers))));
            actions.add(new ProposedAction("logs",
                    "Show the last N lines of a container's logs.", false,
                    List.of(literal("docker"), literal("logs"), literal("--tail"),
                            param("tail"), param("container")),
                    List.of(intRange("tail", 1, MAX_TAIL), allowedSet("container", containers))));
        }
        return List.of(new ProposedRecipe(RecipeType.DOCKER, "docker",
                "Discovered Docker container operations.", actions));
    }
}
