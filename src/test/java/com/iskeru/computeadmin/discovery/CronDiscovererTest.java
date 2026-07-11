package com.iskeru.computeadmin.discovery;

import com.iskeru.computeadmin.discovery.service.CronDiscoverer;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.recipe.model.RecipeType;
import com.iskeru.computeadmin.recipe.service.ActionService.ArgTokenInput;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.iskeru.computeadmin.discovery.FakeSshExecutor.notFound;
import static com.iskeru.computeadmin.discovery.FakeSshExecutor.ok;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CronDiscoverer} against a fake executor (spec-006): a single read-only
 * {@code list} action; add/remove entries are deliberately out of v1 scope.
 *
 * <p>spec-006.
 */
class CronDiscovererTest {

    private final CronDiscoverer discoverer = new CronDiscoverer();

    @Test
    void discover_WithCrontab_ProposesOnlyList() {
        FakeSshExecutor ssh = new FakeSshExecutor(argv -> {
            if (argv.equals(List.of("command", "-v", "crontab"))) {
                return ok("/usr/bin/crontab");
            }
            if (argv.equals(List.of("crontab", "-l"))) {
                return ok("0 3 * * * /usr/local/bin/backup\n");
            }
            if (argv.equals(List.of("ls", "/etc/cron.d"))) {
                return ok("e2scrub_all\n");
            }
            return notFound();
        });

        List<ProposedRecipe> recipes = discoverer.discover(machine(), ssh);

        assertThat(recipes).hasSize(1);
        ProposedRecipe recipe = recipes.get(0);
        assertThat(recipe.type()).isEqualTo(RecipeType.CRON);
        assertThat(recipe.actions()).hasSize(1);
        ProposedAction list = recipe.actions().get(0);
        assertThat(list.name()).isEqualTo("list");
        assertThat(list.sudo()).isFalse();
        assertThat(list.argTokens().stream().map(ArgTokenInput::value).toList())
                .containsExactly("crontab", "-l");
    }

    @Test
    void discover_NoCrontabBinary_ProposesNothing() {
        FakeSshExecutor ssh = new FakeSshExecutor(argv -> notFound());

        assertThat(discoverer.discover(machine(), ssh)).isEmpty();
    }

    private static Machine machine() {
        Machine machine = new Machine();
        machine.setHost("host");
        machine.setPort(22);
        machine.setLoginUser("deploy");
        return machine;
    }
}
