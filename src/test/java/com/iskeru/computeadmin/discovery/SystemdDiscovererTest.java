package com.iskeru.computeadmin.discovery;

import com.iskeru.computeadmin.discovery.service.SystemdDiscoverer;
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
 * {@link SystemdDiscoverer} against a fake executor (spec-026): running units become an
 * {@code app-name} ALLOWED_SET; it proposes status/restart(sudo)/tail-logs(bounded
 * {@code -n}) all keyed by the reserved {@code app-name} param; it only ever sends the
 * two fixed read-only probes — never a mutating command.
 *
 * <p>spec-026.
 */
class SystemdDiscovererTest {

    private static final List<String> PROBE_EXISTS = List.of("command", "-v", "systemctl");
    private static final List<String> PROBE_UNITS =
            List.of("systemctl", "list-units", "--type=service", "--state=running", "--no-legend");

    private final SystemdDiscoverer discoverer = new SystemdDiscoverer();

    @Test
    void discover_WithUnits_ProposesAppNameKeyedOps_ReadOnlyProbesOnly() {
        FakeSshExecutor ssh = new FakeSshExecutor(systemdWith(
                "nginx.service    loaded active running The web server\n"
                        + "orders.service   loaded active running Orders app\n"));

        List<ProposedRecipe> recipes = discoverer.discover(machine(), ssh);

        assertThat(recipes).hasSize(1);
        ProposedRecipe recipe = recipes.get(0);
        assertThat(recipe.type()).isEqualTo(RecipeType.SYSTEMD);
        assertThat(recipe.name()).isEqualTo("systemd");
        assertThat(recipe.actions()).extracting(ProposedAction::name)
                .containsExactly("status", "restart", "tail-logs");

        // Every op is keyed by the reserved `app-name` param, an ALLOWED_SET of the
        // discovered unit names.
        assertThat(allowedSet(recipe, "status", "app-name")).containsExactly("nginx.service", "orders.service");
        assertThat(allowedSet(recipe, "restart", "app-name")).containsExactly("nginx.service", "orders.service");
        assertThat(allowedSet(recipe, "tail-logs", "app-name")).containsExactly("nginx.service", "orders.service");

        // Only restart escalates (S5); status/tail-logs are read-only.
        assertThat(action(recipe, "status").sudo()).isFalse();
        assertThat(action(recipe, "restart").sudo()).isTrue();
        assertThat(action(recipe, "tail-logs").sudo()).isFalse();

        // tail-logs is bounded: a `lines` INT_RANGE, so the journal fetch is one-shot.
        ParamDefInput lines = param(action(recipe, "tail-logs"), "lines");
        assertThat(lines.kind()).isEqualTo(ParamKind.INT_RANGE);
        assertThat(lines.intMin()).isEqualTo(1);
        assertThat(lines.intMax()).isEqualTo(10_000);

        // The discoverer only ever sent the two fixed read-only probes — no mutation.
        assertThat(ssh.commands).containsExactly(PROBE_EXISTS, PROBE_UNITS);
    }

    @Test
    void discover_FiltersUnitNamesOutsideTheAppNameCharset() {
        // The second row's first token "@weird" is outside APP_NAME_PATTERN, so it is
        // dropped and only the valid unit becomes a target app.
        FakeSshExecutor ssh = new FakeSshExecutor(systemdWith(
                "ok.service loaded active running fine\n"
                        + "@weird loaded active running x\n"));

        ProposedRecipe recipe = discoverer.discover(machine(), ssh).get(0);
        assertThat(allowedSet(recipe, "status", "app-name")).containsExactly("ok.service");
    }

    @Test
    void discover_NoRunningUnits_ProposesNothing() {
        FakeSshExecutor ssh = new FakeSshExecutor(systemdWith(""));
        assertThat(discoverer.discover(machine(), ssh)).isEmpty();
    }

    @Test
    void discover_SystemctlNotInstalled_ProposesNothing() {
        FakeSshExecutor ssh = new FakeSshExecutor(argv -> notFound());
        assertThat(discoverer.discover(machine(), ssh)).isEmpty();
        // Only the existence probe was attempted; the unit listing was never sent.
        assertThat(ssh.commands).containsExactly(PROBE_EXISTS);
    }

    private Function<List<String>, ExecResult> systemdWith(String units) {
        return argv -> {
            if (argv.equals(PROBE_EXISTS)) {
                return ok("/usr/bin/systemctl");
            }
            if (argv.equals(PROBE_UNITS)) {
                return ok(units);
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
