package com.iskeru.computeadmin.discovery.service;

import java.util.Locale;
import java.util.Set;

/**
 * The datastore image classification table (spec-033, concern-030 option B): given a
 * container's image reference, decide whether it is a <strong>datastore</strong> (a
 * database / cache / search engine whose footprint is worth attributing) by matching
 * the image's repository name against a fixed prefix set. Pure helper (no suffix, no
 * state), reused by the compose path and by standalone containers.
 *
 * <p>Matching strips the registry host and the {@code :tag}/{@code @digest} suffix,
 * then tests the remaining repository <em>path segments</em> against the known set —
 * so {@code postgres:16}, {@code docker.io/library/postgres}, {@code
 * bitnami/postgresql:15} and {@code mirror.example.com/redis:7-alpine} all classify.
 * It is deliberately a small denylist-ish heuristic (spec-033 Known Gaps): an unusual
 * datastore image falls through to the {@code DOCKER} bucket until its prefix is added
 * here, never mis-firing on an ordinary app image.
 *
 * <p>spec-033.
 */
final class DatastoreImages {

    private DatastoreImages() {
    }

    /**
     * Repository-name tokens that mark a datastore. Kept broad enough to cover the
     * common engines (concern-030 lists {@code postgres|mysql|mariadb|mongo|redis|…})
     * and their obvious variants; a broker (rabbitmq/kafka) is intentionally absent —
     * it lands in the {@code DOCKER} bucket, not the databases lens.
     */
    private static final Set<String> DATASTORE_TOKENS = Set.of(
            "postgres", "postgresql", "mysql", "mariadb", "percona",
            "mongo", "mongodb", "redis", "valkey", "keydb", "memcached",
            "cassandra", "scylladb", "elasticsearch", "opensearch",
            "clickhouse", "cockroachdb", "cockroach", "influxdb", "timescaledb",
            "couchdb", "couchbase", "neo4j", "mssql", "sqlserver");

    /** {@code true} when {@code image} references a known datastore engine. */
    static boolean isDatastore(String image) {
        return engine(image) != null;
    }

    /**
     * The canonical datastore engine name for {@code image} (e.g. {@code postgres}),
     * or {@code null} when the image is not a recognised datastore. Case-insensitive;
     * tolerant of a null/blank image.
     */
    static String engine(String image) {
        if (image == null || image.isBlank()) {
            return null;
        }
        // Drop any @sha256:... digest and the :tag, then the registry host, leaving the
        // repository path (which may still have namespace segments like "bitnami/").
        String ref = image.trim().toLowerCase(Locale.ROOT);
        int at = ref.indexOf('@');
        if (at >= 0) {
            ref = ref.substring(0, at);
        }
        int lastSlash = ref.lastIndexOf('/');
        int colon = ref.indexOf(':', lastSlash + 1);
        if (colon >= 0) {
            ref = ref.substring(0, colon);
        }
        for (String segment : ref.split("/")) {
            if (DATASTORE_TOKENS.contains(segment)) {
                return segment;
            }
        }
        return null;
    }
}
