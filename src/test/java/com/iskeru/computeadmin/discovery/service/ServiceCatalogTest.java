package com.iskeru.computeadmin.discovery.service;

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
}
