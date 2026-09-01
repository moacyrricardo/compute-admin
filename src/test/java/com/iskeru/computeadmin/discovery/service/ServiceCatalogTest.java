package com.iskeru.computeadmin.discovery.service;

import com.iskeru.computeadmin.recipe.model.RecipeType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ServiceCatalog#fingerprintByImage} (spec-061 Decision 3): the docker image-tag mirror of
 * the native process fingerprint. Lives in the {@code discovery.service} package to reach the
 * package-private catalog directly. Proves the shared {@link ImageRef} normalisation (registry
 * host, {@code :tag}, {@code @digest}, {@code bitnami/} namespace all stripped to the engine
 * segment) and that {@code mariadb} never collapses onto the mysql row.
 *
 * <p>spec-061.
 */
class ServiceCatalogTest {

    @Test
    void fingerprintByImage_BarePostgresTag_MatchesPostgresRowWithDataDirEnv() {
        ServiceCatalog.Service row = ServiceCatalog.fingerprintByImage("postgres:16");

        assertThat(row).isNotNull();
        assertThat(row.name()).isEqualTo("postgres");
        assertThat(row.defaultPort()).isEqualTo(5432);
        assertThat(row.dataDirEnvVar()).isEqualTo("PGDATA");
    }

    @Test
    void fingerprintByImage_RegistryAndDigestSuffixed_StillMatches() {
        ServiceCatalog.Service row = ServiceCatalog.fingerprintByImage(
                "mirror.example.com/library/postgres:16-alpine@sha256:0123456789abcdef");

        assertThat(row).isNotNull();
        assertThat(row.name()).isEqualTo("postgres");
    }

    @Test
    void fingerprintByImage_BitnamiPostgresqlVariant_NormalisesToPostgresRow() {
        ServiceCatalog.Service row = ServiceCatalog.fingerprintByImage("bitnami/postgresql:15");

        assertThat(row).isNotNull();
        assertThat(row.name()).isEqualTo("postgres");
    }

    @Test
    void fingerprintByImage_Mariadb_NeverMatchesTheMysqlRow() {
        ServiceCatalog.Service row = ServiceCatalog.fingerprintByImage("mariadb:11");

        assertThat(row).isNotNull();
        assertThat(row.name()).isEqualTo("mariadb");
        assertThat(row.dataDirEnvVar()).isEqualTo("MYSQL_DATADIR");
    }

    @Test
    void fingerprintByImage_Mysql_MatchesMysqlRow() {
        assertThat(ServiceCatalog.fingerprintByImage("mysql:8").name()).isEqualTo("mysql");
    }

    @Test
    void fingerprintByImage_Nginx_MatchesButHasNoDataDirEnv() {
        ServiceCatalog.Service row = ServiceCatalog.fingerprintByImage("nginx:1.27");

        assertThat(row).isNotNull();
        assertThat(row.name()).isEqualTo("nginx");
        assertThat(row.dataDirEnvVar()).isNull();
    }

    @Test
    void fingerprintByImage_AppImageOrUncataloguedDatastore_ReturnsNull() {
        assertThat(ServiceCatalog.fingerprintByImage("orders/web:latest")).isNull();
        assertThat(ServiceCatalog.fingerprintByImage("redis:7-alpine")).isNull();
        assertThat(ServiceCatalog.fingerprintByImage(null)).isNull();
        assertThat(ServiceCatalog.fingerprintByImage("")).isNull();
    }

    // --- spec-075 A1: the port-based fingerprint fallback ------------------------------------

    @Test
    void fingerprintByPort_WellKnownPorts_MatchTheirService() {
        // The A1 table: 80 and 443 both fold to nginx (nginx additionally owns 443); 3306 is mysql
        // (the mysql row wins over the mariadb 3306 alias, like fingerprintByProcess); 5432 postgres.
        assertThat(ServiceCatalog.fingerprintByPort(80).name()).isEqualTo("nginx");
        assertThat(ServiceCatalog.fingerprintByPort(443).name()).isEqualTo("nginx");
        assertThat(ServiceCatalog.fingerprintByPort(3306).name()).isEqualTo("mysql");
        assertThat(ServiceCatalog.fingerprintByPort(5432).name()).isEqualTo("postgres");
    }

    @Test
    void fingerprintByPort_UncataloguedOrInfrastructurePort_ReturnsNull() {
        assertThat(ServiceCatalog.fingerprintByPort(8090)).isNull();
        // The 22/53 skip-set is a discoverer concern; the catalog simply doesn't claim them.
        assertThat(ServiceCatalog.fingerprintByPort(22)).isNull();
        assertThat(ServiceCatalog.fingerprintByPort(53)).isNull();
    }

    // --- spec-075 B2: the fingerprinted-service → typed-family map ---------------------------

    @Test
    void foldFamilyFor_CanonicalServiceNames_MapToTheirTypedFamily() {
        assertThat(ServiceCatalog.foldFamilyFor("nginx")).isEqualTo(RecipeType.NGINX);
        assertThat(ServiceCatalog.foldFamilyFor("postgres")).isEqualTo(RecipeType.DATABASE);
        assertThat(ServiceCatalog.foldFamilyFor("mysql")).isEqualTo(RecipeType.DATABASE);
        assertThat(ServiceCatalog.foldFamilyFor("mariadb")).isEqualTo(RecipeType.DATABASE);
    }

    @Test
    void foldFamilyFor_DaemonSpellingsFromTheAttributedPath_AlsoFold() {
        // The attributed listening path stamps the daemon's own name; those still fold to DATABASE.
        assertThat(ServiceCatalog.foldFamilyFor("postmaster")).isEqualTo(RecipeType.DATABASE);
        assertThat(ServiceCatalog.foldFamilyFor("mysqld")).isEqualTo(RecipeType.DATABASE);
        assertThat(ServiceCatalog.foldFamilyFor("mariadbd")).isEqualTo(RecipeType.DATABASE);
    }

    @Test
    void foldFamilyFor_PlainAppOrNull_FoldsNowhere() {
        assertThat(ServiceCatalog.foldFamilyFor("orders")).isNull();
        assertThat(ServiceCatalog.foldFamilyFor("app-8080")).isNull();
        assertThat(ServiceCatalog.foldFamilyFor(null)).isNull();
    }
}
