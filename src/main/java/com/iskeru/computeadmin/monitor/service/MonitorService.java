package com.iskeru.computeadmin.monitor.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.machine.service.MachineService;
import com.iskeru.computeadmin.monitor.model.Bucket;
import com.iskeru.computeadmin.monitor.model.ConsumerRole;
import com.iskeru.computeadmin.monitor.model.Dedication;
import com.iskeru.computeadmin.recipe.model.Action;
import com.iskeru.computeadmin.recipe.model.ApprovalState;
import com.iskeru.computeadmin.recipe.model.Recipe;
import com.iskeru.computeadmin.recipe.model.RecipeType;
import com.iskeru.computeadmin.recipe.service.ParamBinder;
import com.iskeru.computeadmin.recipe.service.RecipeService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Enumerates the current user's {@link RecipeType#MONITOR}-classified actions so
 * the monitor dashboard (spec-024) can render them, grouped per machine. This is a
 * pure read aggregate: it holds no business rule, mutates nothing, and enforces the
 * gate nowhere — it only <em>lists</em> what already exists, owner-scoped.
 *
 * <p>Enumeration is <strong>by classification</strong>, never by a hard-coded recipe
 * list: any {@code MONITOR}-typed recipe (the universal host monitor of spec-023, the
 * app-monitor families of spec-025) surfaces here without a code change. The
 * host-vs-app split the UI needs is <em>derived</em> per the spec-022 convention — an
 * app-level monitor is a {@code MONITOR} action carrying an {@code APP_PORT_LIST}
 * param, a host-level monitor is one without it — and is computed by the DTO, not
 * stored.
 *
 * <p>Ownership is delegated wholesale to {@link MachineService}/{@link RecipeService}
 * (service→service, never a repository): every machine, recipe, and action returned
 * is already the current user's, so a not-owned id is simply absent (never leaked).
 *
 * <p>spec-024.
 */
@Service
public class MonitorService {

    /**
     * One machine and the {@code MONITOR} recipes (with their actions) it carries, plus
     * its <strong>app-ops actions</strong> ({@code appOps}, spec-026): the APPROVED
     * actions of <em>any</em> recipe type that declare the reserved scalar {@code app-name}
     * param. The dashboard correlates each to an app card by the app-name the action
     * targets, so ops (restart/tail-logs/redeploy) and monitors share one key.
     */
    public record MachineMonitors(Machine machine, List<MonitorRecipe> recipes, List<OpsAction> appOps) {
    }

    /** One app-ops action paired with its recipe (spec-026). */
    public record OpsAction(Recipe recipe, Action action) {
    }

    /**
     * A {@code MONITOR}-typed recipe paired with its (already-loaded) actions and either
     * its discovery-pre-filled {@code (app-name, port)} list (spec-025) — the apps every
     * probe action fans out over — or its classified docker {@code consumers} (spec-033).
     * The two pre-fill channels are mutually exclusive; both are empty for host-vitals
     * (spec-023) and any recipe whose {@code appPortList} is unset.
     */
    public record MonitorRecipe(Recipe recipe, List<Action> actions, List<AppPort> appPortList,
                                List<DockerConsumerData> dockerConsumers) {

        /** A native (or host) monitor recipe with no docker consumers (spec-025). */
        public MonitorRecipe(Recipe recipe, List<Action> actions, List<AppPort> appPortList) {
            this(recipe, actions, appPortList, List.of());
        }
    }

    /** One pre-filled app the dashboard shows/edits and the poller probes (spec-022/025). */
    public record AppPort(String appName, int port, String runtime) {
    }

    /**
     * One docker-sourced consumer parsed from a compose monitor's pre-fill (spec-033):
     * the discovery-side {@code DockerConsumer} re-read on this side. Its classification
     * ({@code role}/{@code dedication}/{@code owner}/{@code usedBy}/{@code bucket}) and
     * {@code services} feed the {@code MonitorConsumerView}; the host-relative axes stay
     * client-filled (no server sampler).
     */
    public record DockerConsumerData(String name, ConsumerRole role, Dedication dedication,
                                     String owner, List<String> usedBy, Bucket bucket,
                                     List<DockerServiceData> services) {
    }

    /** One container inside a docker consumer: its name, image, and classified role. */
    public record DockerServiceData(String name, String image, ConsumerRole role) {
    }

    private final MachineService machineService;
    private final RecipeService recipeService;
    private final ObjectMapper json;

    public MonitorService(MachineService machineService, RecipeService recipeService, ObjectMapper json) {
        this.machineService = machineService;
        this.recipeService = recipeService;
        this.json = json;
    }

    /**
     * Every one of the current user's machines with its {@code MONITOR} recipes and
     * their actions. A machine with no monitor recipe is still returned (an empty host
     * panel) so the dashboard can show it and offer discovery.
     */
    public List<MachineMonitors> listMonitors() {
        return listMonitors(null, null);
    }

    /**
     * The fleet read (spec-029): the current user's machines scoped to a set —
     * {@code tags} narrows by machine tag (OR semantics, delegated to
     * {@link MachineService#list}; null/empty ⇒ every owned machine), and
     * {@code machineIds} further restricts to an explicit in-scope id set (the client's
     * visible selection; null/empty ⇒ no id restriction). Filtering out a machine means
     * it is never enumerated here, so the browser never polls it ("filtered-out =
     * unpolled"). Owner-scoped throughout: a not-owned id is simply absent.
     */
    public List<MachineMonitors> listMonitors(List<String> tags, List<String> machineIds) {
        Set<String> idFilter = machineIds == null ? Set.of()
                : machineIds.stream()
                        .filter(id -> id != null && !id.isBlank())
                        .map(String::trim)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<MachineMonitors> out = new ArrayList<>();
        for (Machine machine : machineService.list(tags)) {
            if (!idFilter.isEmpty() && !idFilter.contains(machine.getId())) {
                continue;
            }
            List<MonitorRecipe> recipes = new ArrayList<>();
            List<OpsAction> appOps = new ArrayList<>();
            for (Recipe recipe : recipeService.listForMachine(machine.getId())) {
                List<Action> actions = recipeService.listActions(recipe.getId());
                if (recipe.getType() == RecipeType.MONITOR) {
                    recipes.add(new MonitorRecipe(recipe, actions,
                            parseAppPortList(recipe.getAppPortList()),
                            parseDockerConsumers(recipe.getAppPortList())));
                }
                // App-ops correlation (spec-026): any APPROVED action carrying the reserved
                // scalar `app-name` param is an ops action, regardless of recipe type. Only
                // approved ops surface — the facade never shows a runnable it would refuse.
                for (Action action : actions) {
                    if (action.getApprovalState() == ApprovalState.APPROVED
                            && ParamBinder.hasReservedAppNameParam(action)) {
                        appOps.add(new OpsAction(recipe, action));
                    }
                }
            }
            out.add(new MachineMonitors(machine, recipes, appOps));
        }
        return out;
    }

    /**
     * Parses a recipe's stored {@code appPortList} JSON ({@code [{"appName","port",
     * "runtime"}]}, spec-025) into structured items for the dashboard. Tolerant: a
     * null/blank/malformed value yields an empty list (the recipe simply has no
     * pre-filled apps yet) rather than failing the whole read.
     */
    private List<AppPort> parseAppPortList(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return List.of();
        }
        List<AppPort> items = new ArrayList<>();
        try {
            JsonNode root = json.readTree(rawJson);
            if (root.isArray()) {
                for (JsonNode node : root) {
                    JsonNode appName = node.get("appName");
                    JsonNode port = node.get("port");
                    JsonNode runtime = node.get("runtime");
                    if (appName != null && port != null) {
                        items.add(new AppPort(appName.asText(), port.asInt(),
                                runtime == null || runtime.isNull() ? null : runtime.asText()));
                    }
                }
            }
        } catch (JsonProcessingException e) {
            return List.of();
        }
        return items;
    }

    /**
     * Parses a docker compose monitor's stored consumers (spec-033) from the same
     * {@code appPortList} column — the object shape {@code {"dockerConsumers":[…]}} the
     * {@code DockerComposeDiscoverer} writes, told apart from the native {@code [{…}]}
     * array by being a JSON object. Tolerant: a null/blank/array/malformed value yields
     * an empty list (a native or host recipe simply has no docker consumers).
     */
    private List<DockerConsumerData> parseDockerConsumers(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return List.of();
        }
        List<DockerConsumerData> consumers = new ArrayList<>();
        try {
            JsonNode root = json.readTree(rawJson);
            JsonNode array = root.get("dockerConsumers");
            if (array == null || !array.isArray()) {
                return List.of();
            }
            for (JsonNode node : array) {
                JsonNode name = node.get("name");
                if (name == null || name.isNull()) {
                    continue;
                }
                consumers.add(new DockerConsumerData(name.asText(),
                        enumOf(ConsumerRole.class, node.get("role")),
                        enumOf(Dedication.class, node.get("dedication")),
                        text(node.get("owner")),
                        stringList(node.get("usedBy")),
                        enumOf(Bucket.class, node.get("bucket")),
                        services(node.get("services"))));
            }
        } catch (JsonProcessingException e) {
            return List.of();
        }
        return consumers;
    }

    private List<DockerServiceData> services(JsonNode array) {
        List<DockerServiceData> out = new ArrayList<>();
        if (array != null && array.isArray()) {
            for (JsonNode node : array) {
                JsonNode name = node.get("name");
                if (name != null && !name.isNull()) {
                    out.add(new DockerServiceData(name.asText(), text(node.get("image")),
                            enumOf(ConsumerRole.class, node.get("role"))));
                }
            }
        }
        return out;
    }

    /** A nullable JSON text value ({@code null} for missing/null nodes). */
    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    /** A tolerant enum read: {@code null} for a missing/null/unknown value. */
    private static <E extends Enum<E>> E enumOf(Class<E> type, JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return Enum.valueOf(type, node.asText());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** A JSON string array as a list ({@code null} node → empty list). */
    private static List<String> stringList(JsonNode array) {
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode node : array) {
            if (!node.isNull()) {
                out.add(node.asText());
            }
        }
        return out;
    }
}
