package com.iskeru.computeadmin.discovery.service;

import com.iskeru.computeadmin.discovery.ProposedAction;
import com.iskeru.computeadmin.discovery.ProposedRecipe;
import com.iskeru.computeadmin.discovery.RecipeDiscoverer;
import com.iskeru.computeadmin.discovery.model.DiscovererFamily;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.recipe.model.RecipeType;
import com.iskeru.computeadmin.ssh.SshSession;
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
 * <p><strong>Standalone sizing (spec-058).</strong> Each engine recipe additionally carries the
 * host-side twin of spec-057's dockerized DB-size probes: a <b>db logical size</b> and a
 * <b>db physical size</b> MONITOR check, both {@code sudo = true}, both fixed and
 * <strong>param-free</strong> {@code sh -c} scripts (so spec-057's client poll selects them exactly
 * as it selects the param-free docker checks). The <em>logical</em> query is identical to 057's —
 * only the transport differs: postgres over peer auth ({@code sudo -u postgres psql}), mysql/mariadb
 * over root socket auth with a {@code --defaults-extra-file=/etc/mysql/debian.cnf} fallback (the app
 * never handles a DB password). The <em>physical</em> probe resolves the engine's own data directory
 * ({@code SHOW data_directory} / {@code SELECT @@datadir}), validates it is an absolute path, then
 * {@code du -sbx} it under {@code timeout 120 nice -n19 ionice -c3}, emitting an {@code onRootFs} flag
 * (spec-041 single-denominator gate: fold physical into the root-FS disk axis only when the datadir
 * lives on the root/data-root filesystem). Absent grant/creds ⇒ {@code permission-denied} at
 * {@code confidence=low}, never a fake {@code 0}. Presentation is spec-059's.
 *
 * <p>spec-006; standalone DB sizing in spec-058.
 */
@Component
public class DatabaseDiscoverer implements RecipeDiscoverer {

    @Override
    public DiscovererFamily family() {
        return DiscovererFamily.DATABASE;
    }

    /** Fixed backup destination; a real path is chosen by the operator at approval. */
    private static final String BACKUP_DIR = "/var/backups/compute-admin";

    /**
     * The <strong>postgres logical-size</strong> probe (spec-058 Decision 2, sudo=true): sum
     * {@code pg_database_size()} over the non-template databases — the exact WHERE clause of the
     * discovery listing (:74) — via peer auth ({@code sudo -u postgres psql}), falling back to the
     * login user's {@code psql}. Empty ⇒ {@code permission-denied} (never a fake {@code 0}).
     * Constant, param-free, read-only.
     */
    static final String PG_LOGICAL_SIZE_SCRIPT = String.join("\n",
            "q='SELECT datname, pg_database_size(datname) FROM pg_database WHERE datistemplate = false'",
            "out=$(sudo -n -u postgres psql -tAF, -c \"$q\" 2>/dev/null)",
            "[ -z \"$out\" ] && out=$(psql -tAF, -c \"$q\" 2>/dev/null)",
            "[ -z \"$out\" ] && { echo 'engine=postgresql logical=permission-denied confidence=low'; exit 0; }",
            "sum=$(printf '%s\\n' \"$out\" | awk -F, '{s+=$2} END {print s+0}')",
            "echo \"engine=postgresql logicalBytes=$sum\"");

    /**
     * The <strong>postgres physical-size</strong> probe (spec-058 Decision 4, sudo=true): ask the
     * engine for its authoritative data directory ({@code SHOW data_directory}), validate it is an
     * absolute path (mirroring {@code ActionService}'s scriptPath guard) before it is bound into the
     * single quoted {@code du} argv element (S4), then {@code du -sbx} it under
     * {@code timeout/nice/ionice}. Emits {@code onRootFs} — whether the datadir's filesystem is the
     * root/data-root mount — so spec-059 folds physical into the spec-041 disk axis only then. The
     * datadir itself is never echoed (S9); only its size and the boolean gate leave the box.
     */
    static final String PG_PHYSICAL_SIZE_SCRIPT = physicalSizeScript(
            "postgresql",
            "sudo -n -u postgres psql -tAc 'SHOW data_directory' 2>/dev/null",
            "psql -tAc 'SHOW data_directory' 2>/dev/null");

    /** MySQL/MariaDB internal schemas excluded from the discovered {@code db} set. */
    private static final Set<String> MYSQL_SYSTEM_DBS =
            Set.of("information_schema", "performance_schema", "mysql", "sys");

    @Override
    public List<ProposedRecipe> discover(Machine machine, SshSession session) {
        List<ProposedRecipe> proposals = new ArrayList<>();

        boolean mysql = Probes.commandExists(session, "mysql");
        boolean mariadb = Probes.commandExists(session, "mariadb");
        if (mysql || mariadb) {
            String binary = mysql ? "mysql" : "mariadb";
            String service = mysql ? "mysql" : "mariadb";
            // Read-only service-status probe (best effort; the status action reports it).
            session.exec(List.of("systemctl", "is-active", service), false);
            List<String> databases = Probes.lines(session,
                            List.of(binary, "-N", "-B", "-e", "SHOW DATABASES")).stream()
                    .filter(db -> !MYSQL_SYSTEM_DBS.contains(db))
                    .toList();
            proposals.add(mysqlRecipe(service, databases));
        }

        if (Probes.commandExists(session, "psql")) {
            String service = "postgresql";
            session.exec(List.of("systemctl", "is-active", service), false);
            List<String> databases = Probes.lines(session,
                    List.of("psql", "-tAc",
                            "SELECT datname FROM pg_database WHERE datistemplate = false"));
            proposals.add(postgresRecipe(service, databases));
        }

        return proposals;
    }

    /**
     * The <strong>mysql/mariadb logical-size</strong> probe (spec-058 Decision 2, sudo=true):
     * {@code SUM(data_length + index_length)} per schema from {@code information_schema} — the same
     * query 057 runs dockerized — over root socket auth, falling back to the root-readable
     * {@code debian-sys-maint} account ({@code --defaults-extra-file=/etc/mysql/debian.cnf}). MySQL
     * prepends {@code SET SESSION information_schema_stats_expiry=0} for fresh (uncached) stats;
     * MariaDB omits it (no such session var). Reuses the {@code -N -B -e} idiom of the discovery
     * listing (:62-63). Empty ⇒ {@code permission-denied}.
     */
    private static String mysqlLogicalScript(String binary, boolean freshStats) {
        String setPrefix = freshStats ? "SET SESSION information_schema_stats_expiry=0; " : "";
        String query = setPrefix + "SELECT table_schema, SUM(data_length+index_length) "
                + "FROM information_schema.tables GROUP BY table_schema";
        return String.join("\n",
                "q='" + query + "'",
                "out=$(" + binary + " -N -B -e \"$q\" 2>/dev/null)",
                "[ -z \"$out\" ] && out=$(" + binary
                        + " --defaults-extra-file=/etc/mysql/debian.cnf -N -B -e \"$q\" 2>/dev/null)",
                "[ -z \"$out\" ] && { echo 'engine=" + binary
                        + " logical=permission-denied confidence=low'; exit 0; }",
                "sum=$(printf '%s\\n' \"$out\" | awk '{s+=$2} END {print s+0}')",
                "echo \"engine=" + binary + " logicalBytes=$sum\"");
    }

    /**
     * The shared <strong>physical-size</strong> probe body (spec-058 Decision 4): resolve the
     * engine-reported data directory (primary then fallback command), validate it is an
     * <strong>absolute path</strong> (a {@code case "$d" in /*)} guard — the shell twin of
     * {@code ActionService}'s scriptPath check) before it is bound as a single quoted {@code du}
     * argv element, then {@code du -sbx} under {@code timeout 120 nice -n19 ionice -c3}. The datadir
     * is <em>never echoed</em> (S9); the output carries only the byte count and the {@code onRootFs}
     * gate ({@code findmnt} says whether the datadir sits on the root {@code /} or data-root
     * {@code /data} filesystem — spec-041's single-denominator invariant). Absent ⇒
     * {@code permission-denied}, never a fake {@code 0}.
     */
    private static String physicalSizeScript(String engine, String primaryDatadirCmd,
                                             String fallbackDatadirCmd) {
        return String.join("\n",
                "d=$(" + primaryDatadirCmd + " | tr -d ' \\n')",
                "[ -z \"$d\" ] && d=$(" + fallbackDatadirCmd + " | tr -d ' \\n')",
                "case \"$d\" in /*) ;; *) echo 'engine=" + engine
                        + " physical=permission-denied confidence=low'; exit 0 ;; esac",
                "pb=$(timeout 120 nice -n19 ionice -c3 du -sbx \"$d\" 2>/dev/null | awk '{print $1}')",
                "[ -z \"$pb\" ] && { echo 'engine=" + engine
                        + " physical=permission-denied confidence=low'; exit 0; }",
                "m=$(findmnt -rno TARGET -T \"$d\" 2>/dev/null)",
                "onroot=false; { [ \"$m\" = / ] || [ \"$m\" = /data ]; } && onroot=true",
                "echo \"engine=" + engine + " physicalBytes=$pb onRootFs=$onroot\"");
    }

    /** The paired standalone DB-size checks (spec-058): logical then physical, both sudo=true. */
    private static List<ProposedAction> sizeActions(String logicalScript, String physicalScript) {
        return List.of(
                new ProposedAction("db logical size",
                        "Served (logical) size — engine SQL aggregate over a host-side transport"
                                + " (peer/socket/config-file auth, no password handled). Read-only.",
                        true, List.of(literal("sh"), literal("-c"), literal(logicalScript)), List.of()),
                new ProposedAction("db physical size",
                        "On-disk (physical) size — du on the engine-reported data directory, under"
                                + " timeout/nice/ionice. Read-only.",
                        true, List.of(literal("sh"), literal("-c"), literal(physicalScript)), List.of()));
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
        // spec-058: standalone logical + physical sizing. MySQL gets the fresh-stats SET; the
        // physical probe resolves @@datadir (root socket → debian.cnf fallback) before du.
        boolean freshStats = "mysql".equals(service);
        actions.addAll(sizeActions(
                mysqlLogicalScript(service, freshStats),
                physicalSizeScript(service,
                        service + " -N -B -e 'SELECT @@datadir' 2>/dev/null",
                        service + " --defaults-extra-file=/etc/mysql/debian.cnf -N -B -e"
                                + " 'SELECT @@datadir' 2>/dev/null")));
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
        // spec-058: standalone logical + physical sizing over peer auth.
        actions.addAll(sizeActions(PG_LOGICAL_SIZE_SCRIPT, PG_PHYSICAL_SIZE_SCRIPT));
        return new ProposedRecipe(RecipeType.DATABASE, service,
                "Discovered " + service + " database operations.", actions);
    }

    private ProposedAction statusAction(String service) {
        return new ProposedAction("status",
                "Report the " + service + " service status (systemctl status). Read-only.", false,
                List.of(literal("systemctl"), literal("status"), literal(service)), List.of());
    }
}
