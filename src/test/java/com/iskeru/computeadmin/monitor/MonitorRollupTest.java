package com.iskeru.computeadmin.monitor;

import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.machine.model.MachineStatus;
import com.iskeru.computeadmin.monitor.api.MonitorDtos;
import com.iskeru.computeadmin.monitor.api.MonitorDtos.MonitorAppView;
import com.iskeru.computeadmin.monitor.api.MonitorDtos.MonitorConsumerView;
import com.iskeru.computeadmin.monitor.api.MonitorDtos.MonitorMachineView;
import com.iskeru.computeadmin.monitor.model.ConsumerRole;
import com.iskeru.computeadmin.monitor.model.ConsumerSource;
import com.iskeru.computeadmin.monitor.service.MonitorService.AppPort;
import com.iskeru.computeadmin.monitor.service.MonitorService.MachineMonitors;
import com.iskeru.computeadmin.monitor.service.MonitorService.MonitorRecipe;
import com.iskeru.computeadmin.monitor.service.MonitorService.OpsAction;
import com.iskeru.computeadmin.recipe.model.Action;
import com.iskeru.computeadmin.recipe.model.ApprovalState;
import com.iskeru.computeadmin.recipe.model.ParamAllowedValue;
import com.iskeru.computeadmin.recipe.model.ParamDef;
import com.iskeru.computeadmin.recipe.model.ParamKind;
import com.iskeru.computeadmin.recipe.model.Recipe;
import com.iskeru.computeadmin.recipe.model.RecipeType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fleet per-app rollup (spec-029) as a pure DTO assembly: {@link MonitorMachineView#of}
 * turns a machine's app-monitor recipes into one {@link MonitorAppView} per pre-filled
 * {@code (app-name, port)} item — sharing the recipe's fan-out probes as {@code checks},
 * correlated to the machine's app-ops by {@code app-name} — plus the pure
 * {@code frameworkOf} classifier. No DB, no wire: the assembly is a pure function of
 * the read aggregate, so it is unit-testable directly.
 *
 * <p>spec-029; the mem-axis helpers ({@code memPctOfHost}/{@code parseHostMemTotalMb})
 * dropped in spec-032 (catalog H8 — the client is the sole source of truth).
 */
class MonitorRollupTest {

    @Test
    void of_BuildsOneAppPerPrefilledItem_SharingChecksAndCorrelatedOps() {
        Machine machine = machine("m1");
        Recipe probeRecipe = recipe("springboot monitor", RecipeType.MONITOR);
        Action health = appProbe(probeRecipe, "health");
        Recipe opsRecipe = recipe("systemd", RecipeType.SYSTEMD);
        Action restart = opsAction(opsRecipe, "restart", "orders");

        MachineMonitors monitors = new MachineMonitors(machine,
                List.of(new MonitorRecipe(probeRecipe, List.of(health),
                        List.of(new AppPort("orders", 8080, "systemd"),
                                new AppPort("billing", 9090, "docker")))),
                List.of(new OpsAction(opsRecipe, restart)));

        MonitorMachineView view = MonitorMachineView.of(monitors);

        assertThat(view.apps()).extracting(MonitorAppView::appName)
                .containsExactly("orders", "billing");

        MonitorAppView orders = view.apps().get(0);
        assertThat(orders.machineId()).isEqualTo("m1");
        assertThat(orders.framework()).isEqualTo("springboot");
        assertThat(orders.port()).isEqualTo(8080);
        assertThat(orders.runtime()).isEqualTo("systemd");
        // The recipe's fan-out probe is the app's check; the approved ops targeting
        // "orders" is correlated onto the same card.
        assertThat(orders.checks()).extracting(v -> v.id()).containsExactly(health.getId());
        assertThat(orders.ops()).extracting(o -> o.id()).containsExactly(restart.getId());
        // Live metrics are the client's to fill from the browser poll — null at assembly.
        assertThat(orders.up()).isNull();
        assertThat(orders.rssMb()).isNull();
        assertThat(orders.hostMemTotalMb()).isNull();
        assertThat(orders.memPctOfHost()).isNull();

        // "billing" shares the same check but has no matching ops.
        MonitorAppView billing = view.apps().get(1);
        assertThat(billing.checks()).extracting(v -> v.id()).containsExactly(health.getId());
        assertThat(billing.ops()).isEmpty();
    }

    @Test
    void of_AssemblesConsumers_FromPrefilledApps_WithSourceFromRuntime() {
        Machine machine = machine("m1");
        Recipe probeRecipe = recipe("springboot monitor", RecipeType.MONITOR);
        Action health = appProbe(probeRecipe, "health");

        MachineMonitors monitors = new MachineMonitors(machine,
                List.of(new MonitorRecipe(probeRecipe, List.of(health),
                        List.of(new AppPort("orders", 8080, "systemd"),
                                new AppPort("billing", 9090, "docker")))),
                List.of());

        MonitorMachineView view = MonitorMachineView.of(monitors);

        // One consumer per pre-filled app, all APPs (spec-032 §3), keyed by app-name.
        assertThat(view.consumers()).extracting(MonitorConsumerView::name)
                .containsExactly("orders", "billing");
        assertThat(view.consumers()).allSatisfy(c ->
                assertThat(c.role()).isEqualTo(ConsumerRole.APP));

        // Source is read from the discoverer's runtime label: a systemd/process app is
        // NATIVE, a container-hosted one is DOCKER (spec-032 §3).
        MonitorConsumerView orders = view.consumers().get(0);
        assertThat(orders.source()).isEqualTo(ConsumerSource.NATIVE);
        assertThat(view.consumers().get(1).source()).isEqualTo(ConsumerSource.DOCKER);

        // The three axes are null at assembly — no server sampler; the client fills them.
        // Disk defaults to null for a native app (no attributable disk, spec-032 §1).
        assertThat(orders.ram()).isNull();
        assertThat(orders.cpu()).isNull();
        assertThat(orders.disk()).isNull();
        // Datastore/bucket labels are unset for native apps in spec-032 (spec-033 fills them).
        assertThat(orders.dedication()).isNull();
        assertThat(orders.owner()).isNull();
        assertThat(orders.usedBy()).isNull();
        assertThat(orders.bucket()).isNull();
        assertThat(orders.services()).isEmpty();
    }

    @Test
    void of_HostOnlyMachine_HasNoApps() {
        Machine machine = machine("m2");
        Recipe hostRecipe = recipe("monitor machine", RecipeType.MONITOR);
        Action cpu = hostProbe(hostRecipe, "cpu");

        MachineMonitors monitors = new MachineMonitors(machine,
                List.of(new MonitorRecipe(hostRecipe, List.of(cpu), List.of())),
                List.of());

        MonitorMachineView view = MonitorMachineView.of(monitors);

        assertThat(view.apps()).isEmpty();
        assertThat(view.consumers()).isEmpty();
        assertThat(view.hostActions()).extracting(v -> v.id()).containsExactly(cpu.getId());
        assertThat(view.appActions()).isEmpty();
    }

    @Test
    void frameworkOf_ClassifiesRecipeFamily_IncludingActuatorlessHttp() {
        assertThat(MonitorDtos.frameworkOf("springboot monitor")).isEqualTo("springboot");
        assertThat(MonitorDtos.frameworkOf("fastapi monitor")).isEqualTo("fastapi");
        assertThat(MonitorDtos.frameworkOf("http app monitor")).isEqualTo("http");
        assertThat(MonitorDtos.frameworkOf("generic app monitor")).isEqualTo("generic");
        assertThat(MonitorDtos.frameworkOf(null)).isEqualTo("generic");
    }

    // --- in-memory fixture builders ----------------------------------------

    private static Machine machine(String id) {
        Machine m = new Machine();
        m.setId(id);
        m.setHost("host-" + id);
        m.setPort(22);
        m.setLoginUser("root");
        m.setStatus(MachineStatus.ONLINE);
        return m;
    }

    private static Recipe recipe(String name, RecipeType type) {
        Recipe r = new Recipe();
        r.setName(name);
        r.setType(type);
        return r;
    }

    /** A fan-out app probe: one {@code APP_PORT_LIST} param → {@code hasAppParam}. */
    private static Action appProbe(Recipe recipe, String name) {
        Action a = new Action();
        a.setName(name);
        a.setRecipe(recipe);
        a.setApprovalState(ApprovalState.APPROVED);
        ParamDef apps = new ParamDef();
        apps.setName("apps");
        apps.setKind(ParamKind.APP_PORT_LIST);
        a.setParamDefs(Set.of(apps));
        return a;
    }

    /** A host probe: no {@code APP_PORT_LIST} → host panel. */
    private static Action hostProbe(Recipe recipe, String name) {
        Action a = new Action();
        a.setName(name);
        a.setRecipe(recipe);
        a.setApprovalState(ApprovalState.APPROVED);
        return a;
    }

    /** An approved app-ops action keyed by the reserved scalar {@code app-name} ALLOWED_SET. */
    private static Action opsAction(Recipe recipe, String name, String... apps) {
        Action a = new Action();
        a.setName(name);
        a.setRecipe(recipe);
        a.setApprovalState(ApprovalState.APPROVED);
        ParamDef appName = new ParamDef();
        appName.setName("app-name");
        appName.setKind(ParamKind.ALLOWED_SET);
        java.util.LinkedHashSet<ParamAllowedValue> values = new java.util.LinkedHashSet<>();
        for (String app : apps) {
            ParamAllowedValue v = new ParamAllowedValue();
            v.setValue(app);
            values.add(v);
        }
        appName.setAllowedValues(values);
        a.setParamDefs(Set.of(appName));
        return a;
    }
}
