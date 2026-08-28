package com.iskeru.computeadmin.discovery;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iskeru.computeadmin.discovery.service.DockerComposeDiscoverer;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.ssh.ExecResult;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Function;

import static com.iskeru.computeadmin.discovery.FakeSshExecutor.notFound;
import static com.iskeru.computeadmin.discovery.FakeSshExecutor.ok;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The spec-061 {@code docker inspect} enrichment of {@link DockerComposeDiscoverer}, driven through
 * the public {@code discover} against a fake executor that answers both {@code docker ps} and the
 * batched {@code docker inspect}. Proves DNAT published-port truth (with the {@code
 * HostConfig.PortBindings} fallback), image-tag fingerprint confidence, the {@code Mounts[]}
 * longest-prefix host-path translation (bind {@code Source} vs named-volume {@code _data}), synthetic
 * context membership, a malformed inspect line degrading to a skipped container, and the no-secrets
 * rule (no {@code Config.Env} value or raw inspect line ever reaches a log or an item field).
 *
 * <p>spec-061.
 */
class DockerInspectEnrichmentTest {

    private final ObjectMapper json = new ObjectMapper();

    private DockerComposeDiscoverer discoverer() {
        return new DockerComposeDiscoverer(json);
    }

    private static String psRow(String id, String names, String image, String labels) {
        return "{\"ID\":\"" + id + "\",\"Names\":\"" + names + "\",\"Image\":\"" + image
                + "\",\"Labels\":\"" + labels + "\"}";
    }

    /** A responder that answers command -v / docker ps / docker inspect with the canned strings. */
    private static Function<List<String>, ExecResult> docker(String psJson, String inspectJson) {
        return argv -> {
            if (argv.equals(List.of("command", "-v", "docker"))) {
                return ok("/usr/bin/docker");
            }
            if (argv.equals(List.of("docker", "ps", "--format", "{{json .}}"))) {
                return ok(psJson);
            }
            if (argv.size() >= 4 && argv.get(0).equals("docker") && argv.get(1).equals("inspect")) {
                return ok(inspectJson);
            }
            return notFound();
        };
    }

    private static AppPortItem item(List<ProposedRecipe> recipes, String recipeName, String appName) {
        return recipes.stream().filter(r -> r.name().equals(recipeName)).findFirst().orElseThrow()
                .appPortList().stream().filter(i -> i.appName().equals(appName)).findFirst().orElseThrow();
    }

    @Test
    void discover_ComposeDatabase_PublishesHostPort_HighConfidence_NamedVolumeSource() {
        String ps = psRow("aaaaaaaaaaaa", "orders-db-1", "postgres:16",
                "com.docker.compose.project=orders,com.docker.compose.service=db");
        String inspect = "{\"Id\":\"aaaaaaaaaaaa1111\",\"Name\":\"/orders-db-1\","
                + "\"Config\":{\"Image\":\"postgres:16\","
                + "\"Labels\":{\"com.docker.compose.project.working_dir\":\"/srv/orders\"},"
                + "\"Env\":[\"PATH=/usr/bin\",\"POSTGRES_PASSWORD=s3cr3t-pw\",\"PGDATA=/var/lib/postgresql/data\"]},"
                + "\"Mounts\":[{\"Type\":\"volume\",\"Name\":\"orders_db\","
                + "\"Source\":\"/var/lib/docker/volumes/orders_db/_data\",\"Destination\":\"/var/lib/postgresql/data\"}],"
                + "\"NetworkSettings\":{\"Ports\":{\"5432/tcp\":[{\"HostIp\":\"0.0.0.0\",\"HostPort\":\"5432\"}]}},"
                + "\"HostConfig\":{\"PortBindings\":{\"5432/tcp\":[{\"HostPort\":\"5432\"}]}}}";

        AppPortItem db = item(discoverer().discover(machine(), new FakeSshExecutor(docker(ps, inspect))),
                "orders", "orders-db-1");

        assertThat(db.port()).isEqualTo(5432);
        assertThat(db.runtime()).isEqualTo("docker");
        assertThat(db.contextKey()).isEqualTo("compose:orders");
        assertThat(db.contextDisplay()).isEqualTo("/srv/orders");
        assertThat(db.confidence()).isEqualTo("high");
        // A named volume's host Source is its _data mountpoint; the data dir matches the mount
        // destination exactly, so scriptFolder is that Source path (Decision 3/4).
        assertThat(db.scriptFolder()).isEqualTo("/var/lib/docker/volumes/orders_db/_data");
        assertThat(db.sourceNote()).isEqualTo(
                "compose project · discovered via docker · published :5432→5432/tcp");
    }

    @Test
    void discover_StandaloneDatabase_UsesPortBindingsFallback_AndBindSourceTranslation() {
        // NetworkSettings.Ports is empty → the DNAT truth comes from HostConfig.PortBindings.
        String ps = psRow("bbbbbbbbbbbb", "pgbind", "postgres:15", "");
        String inspect = "{\"Id\":\"bbbbbbbbbbbb2222\",\"Name\":\"/pgbind\","
                + "\"Config\":{\"Image\":\"postgres:15\",\"Env\":[\"PGDATA=/var/lib/postgresql/data/pgdata\"]},"
                + "\"Mounts\":[{\"Type\":\"bind\",\"Source\":\"/srv/pgdata\",\"Destination\":\"/var/lib/postgresql/data\"}],"
                + "\"NetworkSettings\":{\"Ports\":{}},"
                + "\"HostConfig\":{\"PortBindings\":{\"5432/tcp\":[{\"HostPort\":\"15432\"}]}}}";

        AppPortItem db = item(discoverer().discover(machine(), new FakeSshExecutor(docker(ps, inspect))),
                "pgbind", "pgbind");

        assertThat(db.port()).isEqualTo(15432);
        assertThat(db.contextKey()).isEqualTo("container:pgbind");
        assertThat(db.confidence()).isEqualTo("high");
        // A bind mount's Source carries the path tail beyond the mount destination: the PGDATA
        // override /var/lib/postgresql/data/pgdata → /srv/pgdata + "/pgdata".
        assertThat(db.scriptFolder()).isEqualTo("/srv/pgdata/pgdata");
        assertThat(db.sourceNote()).contains("published :15432→5432/tcp");
    }

    @Test
    void discover_MalformedInspectLine_DegradesToPortlessItem_NeverFails() {
        String ps = String.join("\n",
                psRow("cccccccccccc", "goodapp", "orders/web:latest", ""),
                psRow("dddddddddddd", "brokenapp", "orders/api:latest", ""));
        // A valid doc for goodapp plus a garbage line the parser must skip.
        String inspect = String.join("\n",
                "{\"Id\":\"cccccccccccc3333\",\"Name\":\"/goodapp\",\"Config\":{\"Image\":\"orders/web:latest\"},"
                        + "\"NetworkSettings\":{\"Ports\":{\"80/tcp\":[{\"HostPort\":\"8080\"}]}}}",
                "{ this is not valid json");

        List<ProposedRecipe> recipes =
                discoverer().discover(machine(), new FakeSshExecutor(docker(ps, inspect)));

        // goodapp keeps its published port; brokenapp (skipped inspect) degrades to a sentinel.
        assertThat(item(recipes, "docker containers", "goodapp").port()).isEqualTo(8080);
        AppPortItem broken = item(recipes, "docker containers", "brokenapp");
        assertThat(broken.port()).isZero();
        assertThat(broken.sourceNote()).contains("no published port");
        assertThat(broken.scriptFolder()).isNull();
    }

    @Test
    void discover_NeverLogsOrLeaksAnEnvSecret_NorTheRawInspectLine() {
        Logger logger = (Logger) LoggerFactory.getLogger(DockerComposeDiscoverer.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        Level original = logger.getLevel();
        logger.setLevel(Level.TRACE);
        try {
            String secret = "TOPSECRET-pw-42";
            String ps = String.join("\n",
                    psRow("eeeeeeeeeeee", "sec-db", "postgres:16", ""),
                    psRow("ffffffffffff", "sec-broken", "postgres:16", ""));
            String inspect = String.join("\n",
                    "{\"Id\":\"eeeeeeeeeeee4444\",\"Name\":\"/sec-db\","
                            + "\"Config\":{\"Image\":\"postgres:16\",\"Env\":[\"POSTGRES_PASSWORD=" + secret + "\","
                            + "\"PGDATA=/var/lib/postgresql/data\"]},"
                            + "\"Mounts\":[{\"Type\":\"volume\",\"Source\":\"/vol/_data\",\"Destination\":\"/var/lib/postgresql/data\"}],"
                            + "\"NetworkSettings\":{\"Ports\":{\"5432/tcp\":[{\"HostPort\":\"5432\"}]}}}",
                    // A malformed line that itself carries the secret — must never be logged.
                    "{\"Env\":[\"POSTGRES_PASSWORD=" + secret + "\"] BROKEN");

            List<ProposedRecipe> recipes =
                    discoverer().discover(machine(), new FakeSshExecutor(docker(ps, inspect)));

            // No item field carries the secret (only whitelisted data-dir paths are kept).
            assertThat(recipes).flatExtracting(ProposedRecipe::appPortList).allSatisfy(i -> {
                assertThat(i.scriptFolder() == null ? "" : i.scriptFolder()).doesNotContain(secret);
                assertThat(i.sourceNote()).doesNotContain(secret);
            });
            // No log event — for the valid or the malformed line — carries the secret or raw line.
            assertThat(appender.list).noneMatch(e -> e.getFormattedMessage().contains(secret));
            assertThat(appender.list).noneMatch(e -> e.getFormattedMessage().contains("BROKEN"));
        } finally {
            logger.setLevel(original);
            logger.detachAppender(appender);
        }
    }

    private static Machine machine() {
        Machine machine = new Machine();
        machine.setHost("host");
        machine.setPort(22);
        machine.setLoginUser("deploy");
        return machine;
    }
}
