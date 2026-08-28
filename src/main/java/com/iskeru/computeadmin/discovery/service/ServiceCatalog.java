package com.iskeru.computeadmin.discovery.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The fixed, source-controlled default-folder catalog for the common services the app map
 * fingerprints (spec-056 Decision 5): nginx, postgres, mysql, mariadb, in their Debian/Ubuntu
 * -family layout. A pure helper (no suffix, {@code SlugGenerator}-style per CONTRIBUTING §6) —
 * it holds no state and touches no SSH; the discoverer supplies the observed process/image and,
 * for verification, any {@code Config.Env} override read at the probe site.
 *
 * <p>The rule the discoverer applies is <strong>fingerprint → catalog → verify</strong>:
 * fingerprint the row by process/exe name (native) or image tag (dockerized); look up its
 * default config/data/log folders and port; then <em>verify</em> — a {@code PGDATA}/
 * {@code MYSQL_DATADIR} env override (or, for a dockerized row, a {@code Mounts[]} translation)
 * beats the catalog default before it is trusted. Two agreeing signals (process/image
 * <em>and</em> port) mark the record {@code confidence = high}; a single signal, {@code low}.
 *
 * <p>Only the Debian/Ubuntu-family rows ship here; mongo and non-Debian catalogs are a
 * near-future addition (spec-056 Known Gaps). The dockerized image-tag half of the fingerprint
 * is a follow-on; this class is consumed by the native listening sweep today.
 *
 * <p>spec-056.
 */
final class ServiceCatalog {

    private ServiceCatalog() {
    }

    /**
     * One catalog row: the canonical service {@code name}, its default {@code config}/{@code
     * data}/{@code log} folders and default {@code port}, and the environment variable that,
     * when set on the process, overrides the default data dir ({@code null} when the service
     * has none — nginx).
     */
    record Service(String name, String configDir, String dataDir, String logDir,
                   int defaultPort, String dataDirEnvVar) {
    }

    /** The Debian/Ubuntu-family default-folder rows (spec-056 Decision 5). */
    private static final List<Service> ROWS = List.of(
            new Service("nginx", "/etc/nginx", "/var/www", "/var/log/nginx", 80, null),
            new Service("postgres", "/etc/postgresql", "/var/lib/postgresql",
                    "/var/log/postgresql", 5432, "PGDATA"),
            new Service("mysql", "/etc/mysql", "/var/lib/mysql", "/var/log/mysql", 3306, "MYSQL_DATADIR"),
            new Service("mariadb", "/etc/mysql", "/var/lib/mysql", "/var/log/mysql", 3306, "MYSQL_DATADIR"));

    /**
     * The catalog row a native process fingerprints to (spec-056), matched against its process
     * name and cmdline, or {@code null} when it is not one of the common services. The daemon
     * spellings differ from the row name — {@code postmaster}, {@code mysqld}, {@code mariadbd} —
     * so each is matched explicitly and {@code mariadbd} is never mis-read as {@code mysql}.
     */
    static Service fingerprintByProcess(String process, String cmdline) {
        String haystack = ((process == null ? "" : process) + " "
                + (cmdline == null ? "" : cmdline)).toLowerCase();
        for (Service service : ROWS) {
            if (matches(haystack, service.name())) {
                return service;
            }
        }
        return null;
    }

    private static boolean matches(String haystack, String name) {
        return switch (name) {
            case "postgres" -> haystack.contains("postgres") || haystack.contains("postmaster");
            case "mysql" -> haystack.contains("mysqld");
            case "mariadb" -> haystack.contains("mariadbd");
            default -> haystack.contains(name); // nginx
        };
    }

    /** An nginx {@code root <path>;} directive; the capture group strips surrounding quotes. */
    private static final Pattern NGINX_ROOT = Pattern.compile("^\\s*root\\s+\"?([^;\"\\s]+)\"?\\s*;");

    /** nginx stock default-server document roots to discard when picking the modal real root. */
    private static final java.util.Set<String> NGINX_DEFAULT_ROOTS =
            java.util.Set.of("/usr/share/nginx/html", "/var/www/html");

    /**
     * The nginx real document root from an {@code nginx -T} config dump (spec-062 Decision 4): the
     * <strong>modal</strong> {@code root <path>;} directive after discarding (a) a
     * {@code $}-variable root like {@code root $app_root;} — not a literal path — and (b) a stock
     * default-server root ({@code /usr/share/nginx/html}, {@code /var/www/html}). Surrounding quotes
     * are stripped. Returns {@code null} when the dump is empty, denied, or every directive was
     * filtered — the caller then keeps the catalog default. Pure over the given lines; no SSH.
     */
    static String modalNginxRoot(List<String> nginxTLines) {
        Map<String, Integer> counts = new HashMap<>();
        String modal = null;
        int best = 0;
        for (String line : nginxTLines) {
            Matcher m = NGINX_ROOT.matcher(line);
            if (!m.find()) {
                continue;
            }
            String root = m.group(1);
            if (root.contains("$") || NGINX_DEFAULT_ROOTS.contains(root)) {
                continue;
            }
            int count = counts.merge(root, 1, Integer::sum);
            if (count > best) {
                best = count;
                modal = root;
            }
        }
        return modal;
    }
}
