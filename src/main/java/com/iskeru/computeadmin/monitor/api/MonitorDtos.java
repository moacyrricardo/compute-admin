package com.iskeru.computeadmin.monitor.api;

import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.machine.model.MachineStatus;
import com.iskeru.computeadmin.machine.model.Tag;
import com.iskeru.computeadmin.monitor.service.MonitorService.MachineMonitors;
import com.iskeru.computeadmin.monitor.service.MonitorService.MonitorRecipe;
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
     * (per-app cards), per the spec-022 host-vs-app convention.
     */
    public record MonitorMachineView(String machineId, String host, int port, String loginUser,
                                     MachineStatus status, List<String> tags,
                                     List<MonitorActionView> hostActions,
                                     List<MonitorActionView> appActions) {
        public static MonitorMachineView of(MachineMonitors m) {
            Machine machine = m.machine();
            TreeSet<String> tagNames = new TreeSet<>();
            for (Tag tag : machine.getTags()) {
                tagNames.add(tag.getName());
            }
            List<MonitorActionView> host = new ArrayList<>();
            List<MonitorActionView> app = new ArrayList<>();
            for (MonitorRecipe r : m.recipes()) {
                for (Action action : r.actions()) {
                    MonitorActionView view = MonitorActionView.of(action, r.recipe(), machine.getId());
                    (view.hasAppParam() ? app : host).add(view);
                }
            }
            return new MonitorMachineView(machine.getId(), machine.getHost(), machine.getPort(),
                    machine.getLoginUser(), machine.getStatus(), List.copyOf(tagNames), host, app);
        }
    }

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
                                    List<ArgTokenView> argTokens, List<ParamDefView> paramDefs) {
        public static MonitorActionView of(Action action, Recipe recipe, String machineId) {
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
            return new MonitorActionView(action.getId(), machineId, recipe.getId(), recipe.getName(),
                    recipe.getType(), action.getName(), action.getDescription(), action.isSudo(),
                    action.getApprovalState(), changedSinceApproval, hasAppParam, null, tokens, defs);
        }
    }
}
