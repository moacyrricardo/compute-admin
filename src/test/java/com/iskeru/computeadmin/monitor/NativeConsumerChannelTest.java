package com.iskeru.computeadmin.monitor;

import com.iskeru.computeadmin.monitor.model.ConsumerRole;
import com.iskeru.computeadmin.monitor.model.ConsumerSource;
import com.iskeru.computeadmin.monitor.service.MonitorService;
import com.iskeru.computeadmin.monitor.service.MonitorService.AppPort;
import com.iskeru.computeadmin.monitor.service.MonitorService.NativeConsumerData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The native-consumer channel (spec-063) as a pure function of the parsed
 * {@link AppPort}s: {@link MonitorService#nativeConsumersFrom} groups the pre-filled apps by
 * their internal {@code contextKey} into one {@link NativeConsumerData} per context —
 * {@code source = NATIVE}, {@code role = DATABASE} for a fingerprinted datastore else
 * {@code APP} — the native mirror of {@code parseDockerConsumers}. Docker-cgroup items are
 * excluded (they ride the docker channel, never double-counted).
 *
 * <p>spec-063.
 */
class NativeConsumerChannelTest {

    /** A fully-mapped native app-port item with resolved context (spec-055/056 side-data). */
    private static AppPort item(String appName, int port, String runtime, String contextKey,
                                String contextDisplay, String confidence) {
        return new AppPort(appName, port, runtime, contextKey, contextDisplay,
                List.of(appName), "declared app · discovered", confidence, contextDisplay + "/bin");
    }

    @Test
    void nativeConsumersFrom_GroupsByContext_OneConsumerPerContext_NamedByDisplay() {
        AppPort web = item("orders", 8080, "process", "ctx-1", "/opt/orders", "low");
        AppPort worker = item("orders-worker", 0, "systemd", "ctx-1", "/opt/orders", null);

        List<NativeConsumerData> consumers = MonitorService.nativeConsumersFrom(List.of(web, worker));

        assertThat(consumers).hasSize(1);
        NativeConsumerData c = consumers.get(0);
        assertThat(c.name()).isEqualTo("/opt/orders");
        assertThat(c.contextKey()).isEqualTo("ctx-1");
        assertThat(c.contextDisplay()).isEqualTo("/opt/orders");
        assertThat(c.source()).isEqualTo(ConsumerSource.NATIVE);
        assertThat(c.role()).isEqualTo(ConsumerRole.APP);
        // Both sibling app-scripts collapse into the one context's appNames.
        assertThat(c.appNames()).containsExactly("orders", "orders-worker");
    }

    @Test
    void nativeConsumersFrom_DatastoreGroup_IsDatabaseRole_FromFingerprint() {
        // A 058 standalone-pg listener: process "postgres" on 5432, fingerprint confidence high.
        AppPort pg = item("postgres", 5432, "process", "/var/lib/postgresql",
                "/var/lib/postgresql", "high");

        List<NativeConsumerData> consumers = MonitorService.nativeConsumersFrom(List.of(pg));

        assertThat(consumers).hasSize(1);
        NativeConsumerData c = consumers.get(0);
        assertThat(c.role()).isEqualTo(ConsumerRole.DATABASE);
        assertThat(c.source()).isEqualTo(ConsumerSource.NATIVE);
        assertThat(c.confidence()).isEqualTo("high");
    }

    @Test
    void nativeConsumersFrom_ContextlessApps_AreOwnSingletons_NamedByApp() {
        AppPort a = new AppPort("orders", 8080, "process");   // no resolved context
        AppPort b = new AppPort("billing", 9090, "systemd");  // no resolved context

        List<NativeConsumerData> consumers = MonitorService.nativeConsumersFrom(List.of(a, b));

        assertThat(consumers).extracting(NativeConsumerData::name)
                .containsExactly("orders", "billing");
        assertThat(consumers).allSatisfy(c -> {
            assertThat(c.source()).isEqualTo(ConsumerSource.NATIVE);
            assertThat(c.role()).isEqualTo(ConsumerRole.APP);
            assertThat(c.contextKey()).isNull();
        });
    }

    @Test
    void nativeConsumersFrom_ExcludesDockerRuntimeItems_NoDoubleCount() {
        AppPort process = new AppPort("orders", 8080, "process");
        AppPort dockerItem = item("cache", 6379, "docker", "ctx-docker", "/does/not/matter", "high");

        List<NativeConsumerData> consumers = MonitorService.nativeConsumersFrom(List.of(process, dockerItem));

        // The docker-cgroup item routes to the docker channel; only the native process survives.
        assertThat(consumers).extracting(NativeConsumerData::name).containsExactly("orders");
    }

    @Test
    void isDatastoreName_MatchesEngines_NotNginx() {
        assertThat(MonitorService.isDatastoreName("postgres")).isTrue();
        assertThat(MonitorService.isDatastoreName("postmaster")).isTrue();
        assertThat(MonitorService.isDatastoreName("mysqld")).isTrue();
        assertThat(MonitorService.isDatastoreName("mariadbd")).isTrue();
        assertThat(MonitorService.isDatastoreName("redis-server")).isTrue();
        assertThat(MonitorService.isDatastoreName("nginx")).isFalse();
        assertThat(MonitorService.isDatastoreName("orders")).isFalse();
        assertThat(MonitorService.isDatastoreName(null)).isFalse();
    }
}
