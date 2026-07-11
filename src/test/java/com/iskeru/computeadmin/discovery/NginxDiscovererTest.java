package com.iskeru.computeadmin.discovery;

import com.iskeru.computeadmin.discovery.service.NginxDiscoverer;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.recipe.model.ParamKind;
import com.iskeru.computeadmin.recipe.model.RecipeType;
import com.iskeru.computeadmin.recipe.service.ActionService.ParamDefInput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static com.iskeru.computeadmin.discovery.FakeSshExecutor.notFound;
import static com.iskeru.computeadmin.discovery.FakeSshExecutor.ok;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link NginxDiscoverer} against a fake executor with canned probe output
 * (spec-006): the curated action set, and that the {@code site} ALLOWED_SET values
 * are exactly the discovered paths.
 *
 * <p>spec-006.
 */
class NginxDiscovererTest {

    private final NginxDiscoverer discoverer = new NginxDiscoverer();

    @Test
    void discover_WithSites_ProposesCuratedCatalogWithDiscoveredSites() {
        FakeSshExecutor ssh = new FakeSshExecutor(nginxWith("default\nblog\n", "default\n"));

        List<ProposedRecipe> recipes = discoverer.discover(machine(), ssh);

        assertThat(recipes).hasSize(1);
        ProposedRecipe recipe = recipes.get(0);
        assertThat(recipe.type()).isEqualTo(RecipeType.NGINX);
        assertThat(recipe.name()).isEqualTo("nginx");
        assertThat(recipe.actions()).extracting(ProposedAction::name)
                .containsExactly("test-config", "reload", "restart", "enable-site", "disable-site");

        assertThat(sudoOf(recipe, "test-config")).isFalse();
        assertThat(sudoOf(recipe, "reload")).isTrue();
        assertThat(sudoOf(recipe, "restart")).isTrue();

        assertThat(allowedSet(recipe, "enable-site", "site"))
                .containsExactly("/etc/nginx/sites-available/default", "/etc/nginx/sites-available/blog");
        assertThat(allowedSet(recipe, "disable-site", "site"))
                .containsExactly("/etc/nginx/sites-enabled/default");
    }

    @Test
    void discover_NoSites_OmitsEnableAndDisable() {
        FakeSshExecutor ssh = new FakeSshExecutor(nginxWith("", ""));

        List<ProposedRecipe> recipes = discoverer.discover(machine(), ssh);

        assertThat(recipes.get(0).actions()).extracting(ProposedAction::name)
                .containsExactly("test-config", "reload", "restart");
    }

    @Test
    void discover_NginxNotInstalled_ProposesNothing() {
        FakeSshExecutor ssh = new FakeSshExecutor(argv -> notFound());

        assertThat(discoverer.discover(machine(), ssh)).isEmpty();
    }

    private Function<List<String>, com.iskeru.computeadmin.ssh.ExecResult> nginxWith(String available, String enabled) {
        return argv -> {
            if (argv.equals(List.of("command", "-v", "nginx"))) {
                return ok("/usr/sbin/nginx");
            }
            if (argv.equals(List.of("nginx", "-t"))) {
                return ok("syntax is ok");
            }
            if (argv.equals(List.of("ls", "/etc/nginx/sites-available"))) {
                return ok(available);
            }
            if (argv.equals(List.of("ls", "/etc/nginx/sites-enabled"))) {
                return ok(enabled);
            }
            return notFound();
        };
    }

    private static boolean sudoOf(ProposedRecipe recipe, String actionName) {
        return action(recipe, actionName).sudo();
    }

    private static List<String> allowedSet(ProposedRecipe recipe, String actionName, String paramName) {
        ParamDefInput def = action(recipe, actionName).paramDefs().stream()
                .filter(p -> p.name().equals(paramName))
                .findFirst().orElseThrow();
        assertThat(def.kind()).isEqualTo(ParamKind.ALLOWED_SET);
        return def.allowedValues();
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
