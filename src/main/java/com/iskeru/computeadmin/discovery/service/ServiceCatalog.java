package com.iskeru.computeadmin.discovery.service;

import java.util.List;

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
}
