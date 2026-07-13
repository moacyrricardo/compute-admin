package com.iskeru.computeadmin.monitor.api;

import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.machine.model.MachineStatus;
import com.iskeru.computeadmin.machine.model.Tag;
import com.iskeru.computeadmin.monitor.service.MonitorService.AppPort;
import com.iskeru.computeadmin.monitor.service.MonitorService.MachineMonitors;
import com.iskeru.computeadmin.monitor.service.MonitorService.MonitorRecipe;
import com.iskeru.computeadmin.monitor.service.MonitorService.OpsAction;
import com.iskeru.computeadmin.recipe.api.RecipeDtos.ArgTokenView;
import com.iskeru.computeadmin.recipe.api.RecipeDtos.ParamDefView;
import com.iskeru.computeadmin.recipe.model.Action;
import com.iskeru.computeadmin.recipe.model.ApprovalState;
import com.iskeru.computeadmin.recipe.model.ArgToken;
import com.iskeru.computeadmin.recipe.model.ParamDef;
import com.iskeru.computeadmin.recipe.model.ParamKind;
import com.iskeru.computeadmin.recipe.model.Recipe;
import com.iskeru.computeadmin.recipe.model.RecipeType;
import com.iskeru.computeadmin.recipe.service.ActionSnapshot;
import com.iskeru.computeadmin.recipe.service.ParamBinder;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * DTO records for the monitor dashboard's read surface ({@code GET /api/monitor}).
 * A plain descriptive read source, per ARCH "DTOs": one final class, private ctor,
 * nested records, each response record owning its mapping via a static
 * {@code of(...)}. No mapper framework.
 *
 * <p>The shape mirrors what the dashboard renders (spec-024): the user's machines,
 * each carrying its {@code MONITOR} actions <em>already split</em> into a host panel
 * ({@link MonitorActionView#hasAppParam()} is {@code false}) and per-app probes
 * ({@code hasAppParam} is {@code true}). The split is the sole "business logic" the
 * spec allows the server to pre-compute for the client; grouping app probes into
 * cards by {@code appName} and parsing metric stdout stay in the browser.
 *
 * <p>Every action view also carries its structured argv + param schema and its
 * {@code approvalState}, so the UI can preview and — for an {@code APPROVED} action
 * only — run it inline through the unchanged run path. {@code changedSinceApproval}
 * is the server-derived drift signal (an {@code APPROVED} action whose live content
 * hash no longer matches the hash bound at approval), the same mismatch
 * {@code RunService} rejects at run time; the UI never receives the raw hash.
 *
 * <p>spec-024.
 */
public final class MonitorDtos {

    private MonitorDtos() {
    }

    /** The whole dashboard: the current user's machines and their monitor actions. */
    public record Dashboard(List<MonitorMachineView> machines) {
        public static Dashboard of(List<MachineMonitors> monitors) {
            List<MonitorMachineView> machines = new ArrayList<>();
            for (MachineMonitors m : monitors) {
                machines.add(MonitorMachineView.of(m));
            }
            return new Dashboard(machines);
        }
    }

    /**
     * One machine's monitor surface: its identity/status plus its {@code MONITOR}
     * actions partitioned into {@code hostActions} (host panel) and {@code appActions}
     * (the raw fan-out probes), per the spec-022 host-vs-app convention. {@code apps}
     * is the spec-029 fleet rollup: one {@link MonitorAppView} per discovery-pre-filled
     * {@code (app-name, port)} item, keyed by {@code (machine, app-name)}, unifying an
     * app's fan-out {@code checks} and its matched {@code ops} onto one card.
     */
    public record MonitorMachineView(String machineId, String host, int port, String loginUser,
                                     MachineStatus status, List<String> tags,
                                     List<MonitorActionView> hostActions,
                                     List<MonitorActionView> appActions,
                                     List<AppOpView> appOps,
                                     List<MonitorAppView> apps) {
        public static MonitorMachineView of(MachineMonitors m) {
            Machine machine = m.machine();
            TreeSet<String> tagNames = new TreeSet<>();
            for (Tag tag : machine.getTags()) {
                tagNames.add(tag.getName());
            }
            // App-ops (spec-026): approved actions with a reserved `app-name` param,
            // each carrying the apps it targets so the client can key them to app cards.
            List<AppOpView> appOps = new ArrayList<>();
            for (OpsAction op : m.appOps()) {
                appOps.add(AppOpView.of(op.action(), op.recipe(), machine.getId()));
            }
            List<MonitorActionView> host = new ArrayList<>();
            List<MonitorActionView> app = new ArrayList<>();
            List<MonitorAppView> apps = new ArrayList<>();
            for (MonitorRecipe r : m.recipes()) {
                List<MonitorActionView> recipeChecks = new ArrayList<>();
                for (Action action : r.actions()) {
                    MonitorActionView view = MonitorActionView.of(
                            action, r.recipe(), machine.getId(), r.appPortList());
                    (view.hasAppParam() ? app : host).add(view);
                    if (view.hasAppParam()) {
                        recipeChecks.add(view);
                    }
                }
                // The fleet per-app rollup (spec-029): an app-monitor recipe carries a
                // discovery-pre-filled (app-name, port) list; each item becomes one
                // MonitorAppView keyed by (machine, app-name), sharing the recipe's fan-out
                // probe actions as its `checks` and correlated to the machine's app-ops by
                // app-name. Live metrics (up/rss/mem%) stay null here — the browser fills
                // them from the client-driven poll (024); the server only assembles identity.
                if (!r.appPortList().isEmpty()) {
                    String framework = frameworkOf(r.recipe().getName());
                    for (AppPort item : r.appPortList()) {
                        apps.add(MonitorAppView.of(machine.getId(), item, framework,
                                recipeChecks, opsForApp(appOps, item.appName())));
                    }
                }
            }
            return new MonitorMachineView(machine.getId(), machine.getHost(), machine.getPort(),
                    machine.getLoginUser(), machine.getStatus(), List.copyOf(tagNames), host, app, appOps, apps);
        }
    }

    /**
     * One app-ops action the dashboard can run from an app card (spec-026): an approved
     * mutating action (restart / tail-logs / redeploy) of any recipe type that declares
     * the reserved scalar {@code app-name} param. {@code targetApps} is the {@code
     * app-name} {@code ALLOWED_SET}, the apps this action can target; {@code appParamName}
     * names the param the UI locks to the card's app when pre-filling the run. It carries
     * the full argv + param schema so the run form can prompt the remaining params and
     * preview the exact command, gated by the unchanged run path.
     */
    public record AppOpView(String id, String machineId, String recipeId, String recipeName,
                            RecipeType recipeType, String name, String description, boolean sudo,
                            ApprovalState approvalState, boolean changedSinceApproval,
                            String appParamName, List<String> targetApps,
                            List<ArgTokenView> argTokens, List<ParamDefView> paramDefs) {
        public static AppOpView of(Action action, Recipe recipe, String machineId) {
            List<ParamDefView> defs = new ArrayList<>();
            for (ParamDef def : action.getParamDefs()) {
                defs.add(ParamDefView.of(def));
            }
            List<ArgTokenView> tokens = new ArrayList<>();
            for (ArgToken token : action.getArgTokens()) {
                tokens.add(ArgTokenView.of(token));
            }
            boolean changedSinceApproval = action.getApprovalState() == ApprovalState.APPROVED
                    && !ActionSnapshot.hash(action).equals(action.getApprovedSnapshotHash());
            return new AppOpView(action.getId(), machineId, recipe.getId(), recipe.getName(),
                    recipe.getType(), action.getName(), action.getDescription(), action.isSudo(),
                    action.getApprovalState(), changedSinceApproval,
                    ParamBinder.APP_NAME_COMPONENT, ParamBinder.targetApps(action), tokens, defs);
        }
    }

    /**
     * The app-ops resolver (spec-026): every ops action on {@code machine} that can target
     * {@code appName} — i.e. whose {@code targetApps} contains it (a single-value {@code
     * app-name} equals it). This is the correlation the app card uses to list its runnable
     * ops; it is a pure function of the view, so it is unit-testable without the wire.
     */
    public static List<AppOpView> opsForApp(MonitorMachineView machine, String appName) {
        return opsForApp(machine.appOps(), appName);
    }

    /** {@link #opsForApp(MonitorMachineView, String)} over a raw ops list (assembly-time). */
    private static List<AppOpView> opsForApp(List<AppOpView> appOps, String appName) {
        List<AppOpView> matches = new ArrayList<>();
        for (AppOpView op : appOps) {
            if (op.targetApps().contains(appName)) {
                matches.add(op);
            }
        }
        return matches;
    }

    /**
     * The per-app fleet rollup (spec-029) — the unit the fleet dashboard renders. Keyed
     * by {@code (machineId, appName)}: an app's {@code framework} badge and its
     * {@code runtime}/{@code port} (from the discovery pre-fill), its fan-out
     * {@code checks} (the app-monitor probe actions the client polls), and its matched
     * {@code ops} (spec-026, correlated by {@code app-name}) — checks and ops on one card.
     *
     * <p>The live metrics are the client's to fill from the browser-driven poll (024,
     * no server sampler — see the spec's Known Gaps): {@code up} (rolled up from the
     * checks), {@code rssMb} (summed {@code VmRSS} from the process probe) and
     * {@code hostMemTotalMb} (parsed from the host {@code free -m}, see
     * {@link #parseHostMemTotalMb}). When both memory figures are known the headline
     * {@code memPctOfHost = round(rssMb / hostMemTotalMb * 100)} (spec-029 §5) — the one
     * cross-machine metric — is derived by {@link #memPctOfHost}. Server-side assembly
     * leaves all four {@code null}; a client that has polled recomputes them.
     */
    public record MonitorAppView(String machineId, String appName, String framework, String runtime,
                                 int port, Boolean up, Integer rssMb, Integer hostMemTotalMb,
                                 Integer memPctOfHost, List<MonitorActionView> checks, List<AppOpView> ops) {
        public static MonitorAppView of(String machineId, AppPort app, String framework,
                                        List<MonitorActionView> checks, List<AppOpView> ops) {
            return new MonitorAppView(machineId, app.appName(), framework, app.runtime(), app.port(),
                    null, null, null, null, List.copyOf(checks), List.copyOf(ops));
        }
    }

    /**
     * The headline cross-machine metric (spec-029 §5): an app's resident memory as a
     * whole-percent of its host's total RAM, {@code round(rssMb / hostMemTotalMb * 100)}.
     * {@code null} when either figure is missing (not yet polled) or the host total is
     * non-positive (a divide-by-zero guard) — the client renders that as "no data".
     */
    public static Integer memPctOfHost(Integer rssMb, Integer hostMemTotalMb) {
        if (rssMb == null || hostMemTotalMb == null || hostMemTotalMb <= 0) {
            return null;
        }
        return (int) Math.round((double) rssMb / hostMemTotalMb * 100.0);
    }

    /**
     * Classifies an app-monitor recipe's framework family from its recipe name
     * (spec-025 naming): {@code springboot}, {@code fastapi}, the actuator-less
     * {@code http} family, else {@code generic}. The badge the fleet card shows; the
     * {@code http} family is tagged "actuator-less" by the UI.
     */
    public static String frameworkOf(String recipeName) {
        String s = recipeName == null ? "" : recipeName.toLowerCase(java.util.Locale.ROOT);
        if (s.contains("springboot") || s.contains("spring boot") || s.contains("actuator")) {
            return "springboot";
        }
        if (s.contains("fastapi") || s.contains("uvicorn") || s.contains("gunicorn")) {
            return "fastapi";
        }
        if (s.contains("http")) {
            return "http";
        }
        return "generic";
    }

    /**
     * Parses the host's total memory in MB from {@code free -m} output (spec-023's
     * {@code monitor machine} host-vitals probe) — the {@code Mem:} row's first figure.
     * The denominator for {@link #memPctOfHost}. Tolerant: a null/blank/unparseable
     * value (or a non-positive total) yields {@code null} so the rollup degrades to
     * "no data" rather than throwing.
     */
    public static Integer parseHostMemTotalMb(String freeOutput) {
        if (freeOutput == null || freeOutput.isBlank()) {
            return null;
        }
        for (String line : freeOutput.split("\\r?\\n")) {
            java.util.regex.Matcher m = MEM_TOTAL.matcher(line);
            if (m.find()) {
                int total = Integer.parseInt(m.group(1));
                return total > 0 ? total : null;
            }
        }
        return null;
    }

    /** {@code free -m}'s "Mem: <total> <used> ..." row; group 1 is the total in MB. */
    private static final java.util.regex.Pattern MEM_TOTAL =
            java.util.regex.Pattern.compile("^\\s*Mem:\\s+(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * One {@code MONITOR} action, enriched for grouping and inline running:
     * {@code hasAppParam} says whether it declares an {@code APP_PORT_LIST} param (so
     * the client groups it into a per-app card rather than the host panel), and
     * {@code appName} is the app label when the action carries one directly (an
     * app-ops action, spec-026); for a fan-out app-monitor probe the per-app label is
     * a runtime value carried on each child run's output, so it is {@code null} here.
     */
    public record MonitorActionView(String id, String machineId, String recipeId, String recipeName,
                                    RecipeType recipeType, String name, String description, boolean sudo,
                                    ApprovalState approvalState, boolean changedSinceApproval,
                                    boolean hasAppParam, String appName,
                                    List<ArgTokenView> argTokens, List<ParamDefView> paramDefs,
                                    List<AppPortView> appPortList) {
        public static MonitorActionView of(Action action, Recipe recipe, String machineId,
                                           List<AppPort> appPortList) {
            boolean hasAppParam = false;
            List<ParamDefView> defs = new ArrayList<>();
            for (ParamDef def : action.getParamDefs()) {
                if (def.getKind() == ParamKind.APP_PORT_LIST) {
                    hasAppParam = true;
                }
                defs.add(ParamDefView.of(def));
            }
            List<ArgTokenView> tokens = new ArrayList<>();
            for (ArgToken token : action.getArgTokens()) {
                tokens.add(ArgTokenView.of(token));
            }
            boolean changedSinceApproval = action.getApprovalState() == ApprovalState.APPROVED
                    && !ActionSnapshot.hash(action).equals(action.getApprovedSnapshotHash());
            // A fan-out probe action carries the recipe's pre-filled apps (spec-025) so
            // the dashboard groups per-app cards and knows which ports to poll; a host
            // action (no APP_PORT_LIST) gets an empty list.
            List<AppPortView> apps = new ArrayList<>();
            if (hasAppParam) {
                for (AppPort item : appPortList) {
                    apps.add(AppPortView.of(item));
                }
            }
            return new MonitorActionView(action.getId(), machineId, recipe.getId(), recipe.getName(),
                    recipe.getType(), action.getName(), action.getDescription(), action.isSudo(),
                    action.getApprovalState(), changedSinceApproval, hasAppParam, null, tokens, defs, apps);
        }
    }

    /**
     * One discovery-pre-filled {@code (app-name, port)} item a fan-out probe action
     * runs over (spec-025), with the optional {@code runtime} label (spec-022) the UI
     * uses for the docker/systemd/process affordance and the double-detection link.
     */
    public record AppPortView(String appName, int port, String runtime) {
        public static AppPortView of(AppPort item) {
            return new AppPortView(item.appName(), item.port(), item.runtime());
        }
    }
}
