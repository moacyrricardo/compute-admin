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

        List<ProposedRecipe> recipes = discoverer.discover(machine(), ssh.session());

        assertThat(recipes).hasSize(1);
        ProposedRecipe recipe = recipes.get(0);
        assertThat(recipe.type()).isEqualTo(RecipeType.DATABASE);
        assertThat(recipe.name()).isEqualTo("mysql");
        // status + backup, plus the spec-058 standalone size checks appended to every engine recipe.
        assertThat(recipe.actions()).extracting(ProposedAction::name)
                .containsExactly("status", "backup", "db logical size", "db physical size");

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

        List<ProposedRecipe> recipes = discoverer.discover(machine(), ssh.session());

        assertThat(recipes).hasSize(1);
        assertThat(recipes.get(0).name()).isEqualTo("postgresql");
        assertThat(allowedSet(recipes.get(0), "backup", "db")).containsExactly("postgres", "shopdb");
    }

    @Test
    void discover_NoDatabaseEngine_ProposesNothing() {
        FakeSshExecutor ssh = new FakeSshExecutor(argv -> notFound());

        assertThat(discoverer.discover(machine(), ssh.session())).isEmpty();
    }

    // --- spec-058: standalone DB sizing ------------------------------------

    @Test
    void discover_PostgresLogicalSize_ParamFreeSudo_ReusesPgDatabaseSizeQuery() {
        ProposedRecipe pg = recipe(discoverer.discover(machine(), postgresSsh().session()), "postgresql");
        ProposedAction logical = action(pg, "db logical size");

        // Param-free ⇒ 057's client poll selects it like the docker checks; sudo=true (Decision 5).
        assertThat(logical.paramDefs()).isEmpty();
        assertThat(logical.sudo()).isTrue();

        String script = scriptOf(logical);
        // Decision 2: the exact non-template pg_database_size aggregate 057 runs, over peer auth
        // (Decision 3) with a login-user psql fallback; degrade label, never a fake 0.
        assertThat(script)
                .contains("pg_database_size(datname)")
                .contains("datistemplate = false")
                .contains("sudo -n -u postgres psql")
                .contains("psql -tAF, -c")
                .contains("logicalBytes=")
                .contains("permission-denied");
        assertThat(script.length()).isLessThan(1024);
    }

    @Test
    void discover_MysqlLogicalSize_FreshStats_AndDebianCnfFallback() {
        ProposedRecipe my = recipe(discoverer.discover(machine(), mysqlSsh("mysql").session()), "mysql");
        String script = scriptOf(action(my, "db logical size"));

        // Decision 2: information_schema SUM over the -N -B -e idiom (:62-63); MySQL prepends the
        // fresh-stats SET; Decision 3: root socket auth then the debian.cnf maintenance account.
        assertThat(script)
                .contains("SUM(data_length+index_length)")
                .contains("information_schema.tables")
                .contains("SET SESSION information_schema_stats_expiry=0")
                .contains("mysql -N -B -e")
                .contains("--defaults-extra-file=/etc/mysql/debian.cnf")
                .contains("permission-denied");
    }

    @Test
    void discover_MariadbLogicalSize_OmitsFreshStatsSet() {
        ProposedRecipe md = recipe(discoverer.discover(machine(), mysqlSsh("mariadb").session()), "mariadb");
        String script = scriptOf(action(md, "db logical size"));

        // MariaDB has no information_schema_stats_expiry session var — the SET is omitted.
        assertThat(script)
                .contains("SUM(data_length+index_length)")
                .contains("mariadb -N -B -e")
                .doesNotContain("information_schema_stats_expiry");
    }

    @Test
    void discover_PhysicalSize_VerifiesDatadir_GuardsAbsolutePath_AndGatesRootFs() {
        ProposedRecipe pg = recipe(discoverer.discover(machine(), postgresSsh().session()), "postgresql");
        ProposedAction physical = action(pg, "db physical size");
        assertThat(physical.sudo()).isTrue();
        assertThat(physical.paramDefs()).isEmpty();

        String script = scriptOf(physical);
        // Decision 4: authoritative datadir from the engine, absolute-path guard before it is bound
        // into the single quoted du argv element (S4), du under timeout/nice/ionice, and the
        // spec-041 root/data-root FS gate emitted as onRootFs.
        assertThat(script)
                .contains("SHOW data_directory")
                .contains("case \"$d\" in /*)")
                .contains("timeout 120 nice -n19 ionice -c3 du -sbx \"$d\"")
                .contains("findmnt -rno TARGET -T \"$d\"")
                .contains("[ \"$m\" = / ] || [ \"$m\" = /data ]")
                .contains("physicalBytes=")
                .contains("onRootFs=");
    }

    @Test
    void discover_PhysicalSize_NeverEchoesTheDatadir() {
        // S9: the resolved datadir is a secret internal path — it drives du but must never leave
        // the box on the run-output seam. Only the byte count and the boolean gate are echoed.
        ProposedRecipe my = recipe(discoverer.discover(machine(), mysqlSsh("mysql").session()), "mysql");
        String script = scriptOf(action(my, "db physical size"));
        String echoLine = script.lines().filter(l -> l.startsWith("echo")).findFirst().orElseThrow();
        assertThat(echoLine).doesNotContain("$d").doesNotContain("datadir=");
    }

    @Test
    void discover_SizeProbes_DegradeToPermissionDenied_NeverFakeZero() {
        // Decision 5: every size script's no-privilege branch emits permission-denied at
        // confidence=low — it never fabricates a 0.
        for (ProposedRecipe recipe : List.of(
                recipe(discoverer.discover(machine(), postgresSsh().session()), "postgresql"),
                recipe(discoverer.discover(machine(), mysqlSsh("mysql").session()), "mysql"))) {
            for (String name : List.of("db logical size", "db physical size")) {
                assertThat(scriptOf(action(recipe, name)))
                        .contains("permission-denied")
                        .contains("confidence=low");
            }
        }
    }

    private static FakeSshExecutor postgresSsh() {
        return new FakeSshExecutor(argv -> {
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
    }

    private static FakeSshExecutor mysqlSsh(String binary) {
        return new FakeSshExecutor(argv -> {
            if (argv.equals(List.of("command", "-v", binary))) {
                return ok("/usr/bin/" + binary);
            }
            if (argv.equals(List.of("systemctl", "is-active", binary))) {
                return ok("active");
            }
            if (argv.equals(List.of(binary, "-N", "-B", "-e", "SHOW DATABASES"))) {
                return ok("information_schema\nappdb\n");
            }
            return notFound();
        });
    }

    private static ProposedRecipe recipe(List<ProposedRecipe> recipes, String name) {
        return recipes.stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
    }

    private static String scriptOf(ProposedAction action) {
        // The sh -c script is the third argv token: sh, -c, <script>.
        return action.argTokens().get(2).value();
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
