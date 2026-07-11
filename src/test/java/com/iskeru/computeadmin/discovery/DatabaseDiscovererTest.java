package com.iskeru.computeadmin.discovery;

import com.iskeru.computeadmin.discovery.service.DatabaseDiscoverer;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.recipe.model.RecipeType;
import com.iskeru.computeadmin.recipe.service.ActionService.ArgTokenInput;
import com.iskeru.computeadmin.recipe.service.ActionService.ParamDefInput;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.iskeru.computeadmin.discovery.FakeSshExecutor.notFound;
import static com.iskeru.computeadmin.discovery.FakeSshExecutor.ok;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DatabaseDiscoverer} against a fake executor (spec-006): one recipe per
 * engine present, system schemas filtered out of the discovered {@code db} set, and
 * the {@code status} action naming the detected service.
 *
 * <p>spec-006.
 */
class DatabaseDiscovererTest {

    private final DatabaseDiscoverer discoverer = new DatabaseDiscoverer();

    @Test
    void discover_Mysql_ProposesStatusAndBackupWithUserDatabasesOnly() {
        FakeSshExecutor ssh = new FakeSshExecutor(argv -> {
            if (argv.equals(List.of("command", "-v", "mysql"))) {
                return ok("/usr/bin/mysql");
            }
            if (argv.equals(List.of("systemctl", "is-active", "mysql"))) {
                return ok("active");
            }
            if (argv.equals(List.of("mysql", "-N", "-B", "-e", "SHOW DATABASES"))) {
                return ok("information_schema\nappdb\nsys\nmysql\norders\n");
            }
            return notFound();
        });

        List<ProposedRecipe> recipes = discoverer.discover(machine(), ssh);

        assertThat(recipes).hasSize(1);
        ProposedRecipe recipe = recipes.get(0);
        assertThat(recipe.type()).isEqualTo(RecipeType.DATABASE);
        assertThat(recipe.name()).isEqualTo("mysql");
        assertThat(recipe.actions()).extracting(ProposedAction::name).containsExactly("status", "backup");

        assertThat(argTokenValues(action(recipe, "status")))
                .containsExactly("systemctl", "status", "mysql");
        assertThat(allowedSet(recipe, "backup", "db")).containsExactly("appdb", "orders");
    }

    @Test
    void discover_Postgres_ProposesStatusAndBackup() {
        FakeSshExecutor ssh = new FakeSshExecutor(argv -> {
            if (argv.equals(List.of("command", "-v", "psql"))) {
                return ok("/usr/bin/psql");
            }
            if (argv.equals(List.of("systemctl", "is-active", "postgresql"))) {
                return ok("active");
            }
            if (argv.equals(List.of("psql", "-tAc",
                    "SELECT datname FROM pg_database WHERE datistemplate = false"))) {
                return ok("postgres\nshopdb\n");
            }
            return notFound();
        });

        List<ProposedRecipe> recipes = discoverer.discover(machine(), ssh);

        assertThat(recipes).hasSize(1);
        assertThat(recipes.get(0).name()).isEqualTo("postgresql");
        assertThat(allowedSet(recipes.get(0), "backup", "db")).containsExactly("postgres", "shopdb");
    }

    @Test
    void discover_NoDatabaseEngine_ProposesNothing() {
        FakeSshExecutor ssh = new FakeSshExecutor(argv -> notFound());

        assertThat(discoverer.discover(machine(), ssh)).isEmpty();
    }

    private static List<String> argTokenValues(ProposedAction action) {
        return action.argTokens().stream().map(ArgTokenInput::value).toList();
    }

    private static List<String> allowedSet(ProposedRecipe recipe, String actionName, String paramName) {
        ParamDefInput def = action(recipe, actionName).paramDefs().stream()
                .filter(p -> p.name().equals(paramName))
                .findFirst().orElseThrow();
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
