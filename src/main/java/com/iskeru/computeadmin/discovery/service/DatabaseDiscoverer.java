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
import java.util.Set;

import static com.iskeru.computeadmin.discovery.Proposals.allowedSet;
import static com.iskeru.computeadmin.discovery.Proposals.literal;
import static com.iskeru.computeadmin.discovery.Proposals.param;

/**
 * Discovers MySQL/MariaDB and/or PostgreSQL and proposes one curated recipe per
 * engine present. Probes (fixed, read-only): {@code command -v} for each binary,
 * {@code systemctl is-active <svc>} for the service, and a read-only database
 * listing ({@code SHOW DATABASES} / {@code SELECT datname FROM pg_database}).
 *
 * <p>Actions proposed (all land {@code PENDING_APPROVAL}): <b>status</b>
 * ({@code systemctl status <svc>}, read-only) and — only when non-system databases
 * were discovered — <b>backup</b> ({@code mysqldump}/{@code pg_dump} of a {@code db}
 * from the discovered closed set, written to a fixed backup dir). The {@code db}
 * set is attacker-influenced (S3); the human approval step is the mitigation.
 *
 * <p>spec-006.
 */
@Component
public class DatabaseDiscoverer implements RecipeDiscoverer {

    @Override
    public DiscovererFamily family() {
        return DiscovererFamily.DATABASE;
    }

    /** Fixed backup destination; a real path is chosen by the operator at approval. */
    private static final String BACKUP_DIR = "/var/backups/compute-admin";

    /** MySQL/MariaDB internal schemas excluded from the discovered {@code db} set. */
    private static final Set<String> MYSQL_SYSTEM_DBS =
            Set.of("information_schema", "performance_schema", "mysql", "sys");

    @Override
    public List<ProposedRecipe> discover(Machine machine, SshExecutor ssh) {
        SshTarget target = Probes.target(machine);
        List<ProposedRecipe> proposals = new ArrayList<>();

        boolean mysql = Probes.commandExists(ssh, target, "mysql");
        boolean mariadb = Probes.commandExists(ssh, target, "mariadb");
        if (mysql || mariadb) {
            String binary = mysql ? "mysql" : "mariadb";
            String service = mysql ? "mysql" : "mariadb";
            // Read-only service-status probe (best effort; the status action reports it).
            ssh.exec(target, List.of("systemctl", "is-active", service), false);
            List<String> databases = Probes.lines(ssh, target,
                            List.of(binary, "-N", "-B", "-e", "SHOW DATABASES")).stream()
                    .filter(db -> !MYSQL_SYSTEM_DBS.contains(db))
                    .toList();
            proposals.add(mysqlRecipe(service, databases));
        }

        if (Probes.commandExists(ssh, target, "psql")) {
            String service = "postgresql";
            ssh.exec(target, List.of("systemctl", "is-active", service), false);
            List<String> databases = Probes.lines(ssh, target,
                    List.of("psql", "-tAc",
                            "SELECT datname FROM pg_database WHERE datistemplate = false"));
            proposals.add(postgresRecipe(service, databases));
        }

        return proposals;
    }

    private ProposedRecipe mysqlRecipe(String service, List<String> databases) {
        List<ProposedAction> actions = new ArrayList<>();
        actions.add(statusAction(service));
        if (!databases.isEmpty()) {
            actions.add(new ProposedAction("backup",
                    "Dump a database with mysqldump into " + BACKUP_DIR + ".", false,
                    List.of(literal("mysqldump"), literal("--result-file"),
                            literal(BACKUP_DIR + "/mysql-backup.sql"), param("db")),
                    List.of(allowedSet("db", databases))));
        }
        return new ProposedRecipe(RecipeType.DATABASE, service,
                "Discovered " + service + " database operations.", actions);
    }

    private ProposedRecipe postgresRecipe(String service, List<String> databases) {
        List<ProposedAction> actions = new ArrayList<>();
        actions.add(statusAction(service));
        if (!databases.isEmpty()) {
            actions.add(new ProposedAction("backup",
                    "Dump a database with pg_dump into " + BACKUP_DIR + ".", false,
                    List.of(literal("pg_dump"), literal("--file"),
                            literal(BACKUP_DIR + "/postgres-backup.sql"), param("db")),
                    List.of(allowedSet("db", databases))));
        }
        return new ProposedRecipe(RecipeType.DATABASE, service,
                "Discovered " + service + " database operations.", actions);
    }

    private ProposedAction statusAction(String service) {
        return new ProposedAction("status",
                "Report the " + service + " service status (systemctl status). Read-only.", false,
                List.of(literal("systemctl"), literal("status"), literal(service)), List.of());
    }
}
