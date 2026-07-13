package com.iskeru.computeadmin.discovery;

import com.iskeru.computeadmin.discovery.service.MonitorMachineDiscoverer;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.recipe.model.RecipeType;
import com.iskeru.computeadmin.recipe.model.TokenKind;
import com.iskeru.computeadmin.recipe.service.ActionService.ArgTokenInput;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.iskeru.computeadmin.discovery.FakeSshExecutor.notFound;
import static com.iskeru.computeadmin.discovery.FakeSshExecutor.ok;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MonitorMachineDiscoverer} against a fake executor (spec-023): the universal
 * host-vitals catalog — one {@code monitor machine} recipe with the four read-only,
 * param-free actions cpu/memory/disk/cores (nproc, spec-037), each a fixed argv, none
 * using sudo — proposed
 * <strong>regardless</strong> of probe output (it gates on nothing) and never
 * auto-approved (a discoverer only proposes).
 *
 * <p>spec-023.
 */
class MonitorMachineDiscovererTest {

    private final MonitorMachineDiscoverer discoverer = new MonitorMachineDiscoverer();

    @Test
    void discover_ProposesMonitorMachineWithThreeReadOnlyActions() {
        FakeSshExecutor ssh = new FakeSshExecutor(argv -> ok("anything"));

        List<ProposedRecipe> recipes = discoverer.discover(machine(), ssh);

        assertThat(recipes).hasSize(1);
        ProposedRecipe recipe = recipes.get(0);
        assertThat(recipe.type()).isEqualTo(RecipeType.MONITOR);
        assertThat(recipe.name()).isEqualTo("monitor machine");
        assertThat(recipe.actions()).extracting(ProposedAction::name)
                .containsExactly("cpu", "memory", "disk", "cores");

        // All three are read-only (no sudo) and take no params → host-level, no fan-out.
        assertThat(recipe.actions()).allSatisfy(action -> {
            assertThat(action.sudo()).isFalse();
            assertThat(action.paramDefs()).isEmpty();
        });

        assertThat(argv(recipe, "cpu")).containsExactly("top", "-bn1");
        assertThat(argv(recipe, "memory")).containsExactly("free", "-m");
        assertThat(argv(recipe, "disk")).containsExactly("df", "-h");
        // spec-037: the nproc host vital, the docker CPU-axis denominator.
        assertThat(argv(recipe, "cores")).containsExactly("nproc");
    }

    @Test
    void discover_IsUniversal_ProposesEvenWhenEveryProbeFails() {
        // Every command "fails" — a service-gated discoverer would propose nothing;
        // this one is universal and proposes the host monitor anyway.
        FakeSshExecutor ssh = new FakeSshExecutor(argv -> notFound());

        List<ProposedRecipe> recipes = discoverer.discover(machine(), ssh);

        assertThat(recipes).extracting(ProposedRecipe::name).containsExactly("monitor machine");
    }

    @Test
    void discover_SendsNoProbe_GatesOnNothing() {
        // Universal ⇒ it need not (and does not) SSH to decide; the recording fake
        // shows it never issued a probe just to propose.
        FakeSshExecutor ssh = new FakeSshExecutor(argv -> ok(""));

        discoverer.discover(machine(), ssh);

        assertThat(ssh.commands).isEmpty();
    }

    private static List<String> argv(ProposedRecipe recipe, String actionName) {
        return recipe.actions().stream()
                .filter(a -> a.name().equals(actionName))
                .findFirst().orElseThrow()
                .argTokens().stream()
                .peek(token -> assertThat(token.kind()).isEqualTo(TokenKind.LITERAL))
                .map(ArgTokenInput::value)
                .toList();
    }

    private static Machine machine() {
        Machine machine = new Machine();
        machine.setHost("host");
        machine.setPort(22);
        machine.setLoginUser("deploy");
        return machine;
    }
}
