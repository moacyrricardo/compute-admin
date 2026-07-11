package com.iskeru.computeadmin.discovery;

import com.iskeru.computeadmin.discovery.service.DockerDiscoverer;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.recipe.model.ParamKind;
import com.iskeru.computeadmin.recipe.model.RecipeType;
import com.iskeru.computeadmin.recipe.service.ActionService.ParamDefInput;
import com.iskeru.computeadmin.ssh.ExecResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static com.iskeru.computeadmin.discovery.FakeSshExecutor.notFound;
import static com.iskeru.computeadmin.discovery.FakeSshExecutor.ok;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DockerDiscoverer} against a fake executor (spec-006): the curated catalog,
 * the {@code container} ALLOWED_SET drawn from discovered names, and the
 * {@code --tail} INT_RANGE on logs.
 *
 * <p>spec-006.
 */
class DockerDiscovererTest {

    private final DockerDiscoverer discoverer = new DockerDiscoverer();

    @Test
    void discover_WithContainers_ProposesCuratedCatalogWithDiscoveredNames() {
        FakeSshExecutor ssh = new FakeSshExecutor(dockerWith("web\ndb\n"));

        List<ProposedRecipe> recipes = discoverer.discover(machine(), ssh);

        assertThat(recipes).hasSize(1);
        ProposedRecipe recipe = recipes.get(0);
        assertThat(recipe.type()).isEqualTo(RecipeType.DOCKER);
        assertThat(recipe.actions()).extracting(ProposedAction::name)
                .containsExactly("ps", "restart container", "stop container", "start container", "logs");

        assertThat(allowedSet(recipe, "restart container", "container")).containsExactly("web", "db");

        ProposedAction logs = action(recipe, "logs");
        assertThat(allowedSet(recipe, "logs", "container")).containsExactly("web", "db");
        ParamDefInput tail = param(logs, "tail");
        assertThat(tail.kind()).isEqualTo(ParamKind.INT_RANGE);
        assertThat(tail.intMin()).isEqualTo(1);
        assertThat(tail.intMax()).isEqualTo(10_000);
    }

    @Test
    void discover_NoContainers_ProposesOnlyPs() {
        FakeSshExecutor ssh = new FakeSshExecutor(dockerWith(""));

        assertThat(discoverer.discover(machine(), ssh).get(0).actions())
                .extracting(ProposedAction::name).containsExactly("ps");
    }

    @Test
    void discover_DockerNotInstalled_ProposesNothing() {
        FakeSshExecutor ssh = new FakeSshExecutor(argv -> notFound());

        assertThat(discoverer.discover(machine(), ssh)).isEmpty();
    }

    private Function<List<String>, ExecResult> dockerWith(String names) {
        return argv -> {
            if (argv.equals(List.of("command", "-v", "docker"))) {
                return ok("/usr/bin/docker");
            }
            if (argv.equals(List.of("docker", "ps", "--format", "{{.Names}}"))) {
                return ok(names);
            }
            return notFound();
        };
    }

    private static List<String> allowedSet(ProposedRecipe recipe, String actionName, String paramName) {
        return param(action(recipe, actionName), paramName).allowedValues();
    }

    private static ParamDefInput param(ProposedAction action, String paramName) {
        return action.paramDefs().stream()
                .filter(p -> p.name().equals(paramName))
                .findFirst().orElseThrow();
    }

    private static ProposedAction action(ProposedRecipe recipe, String name) {
        return recipe.actions().stream().filter(a -> a.name().equals(name)).findFirst().orElseThrow();
    }

    private static Machine machine() {
        Machine machine = new Machine();
        machine.setHost("host");
        machine.setPort(22);
        machine.setLoginUser("deploy");
        return machine;
    }
}
