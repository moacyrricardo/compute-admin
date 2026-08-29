package com.iskeru.computeadmin.monitor.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.machine.service.MachineService;
import com.iskeru.computeadmin.monitor.model.Bucket;
import com.iskeru.computeadmin.monitor.model.ConsumerRole;
import com.iskeru.computeadmin.monitor.model.ConsumerSource;
import com.iskeru.computeadmin.monitor.model.Dedication;
import com.iskeru.computeadmin.recipe.model.Action;
import com.iskeru.computeadmin.recipe.model.ApprovalState;
import com.iskeru.computeadmin.recipe.model.Recipe;
import com.iskeru.computeadmin.recipe.model.RecipeType;
import com.iskeru.computeadmin.recipe.service.ParamBinder;
import com.iskeru.computeadmin.recipe.service.RecipeService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
                                List<DockerConsumerData> dockerConsumers,
                                List<NativeConsumerData> nativeConsumers) {

        /**
         * A native (or docker) monitor recipe whose native consumers are derived from its
         * pre-filled apps (spec-063): the same {@code contextKey}-grouping the JSON read path
         * applies, so a test-built recipe and a persisted one classify identically.
         */
        public MonitorRecipe(Recipe recipe, List<Action> actions, List<AppPort> appPortList,
                             List<DockerConsumerData> dockerConsumers) {
            this(recipe, actions, appPortList, dockerConsumers, nativeConsumersFrom(appPortList));
        }

        /** A native (or host) monitor recipe with no docker consumers (spec-025). */
        public MonitorRecipe(Recipe recipe, List<Action> actions, List<AppPort> appPortList) {
            this(recipe, actions, appPortList, List.of());
        }
    }

    /**
     * One pre-filled app the dashboard shows/edits and the poller probes (spec-022/025),
     * carrying the rich discovery-context side-data (spec-063): the logical
     * {@code contextDisplay}/{@code scriptFolder} paths, the sibling {@code contextScripts},
     * the {@code sourceNote} provenance, the fingerprint {@code confidence}, and the internal
     * {@code contextKey} identity used to group native consumers. {@code contextKey} is the
     * S9-secret dedup key — it never leaves the service (no DTO carries it).
     */
    public record AppPort(String appName, int port, String runtime,
                          String contextKey, String contextDisplay, List<String> contextScripts,
                          String sourceNote, String confidence, String scriptFolder,
                          Integer managementPort) {

        public AppPort {
            contextScripts = contextScripts == null ? List.of() : List.copyOf(contextScripts);
        }

        /** The pre-073 nine-field item (single-port app: no separate management port). */
        public AppPort(String appName, int port, String runtime,
                       String contextKey, String contextDisplay, List<String> contextScripts,
                       String sourceNote, String confidence, String scriptFolder) {
            this(appName, port, runtime, contextKey, contextDisplay, contextScripts,
                    sourceNote, confidence, scriptFolder, null);
        }

        /** The bare three-field item (no resolved context) — old rows and docker-object items. */
        public AppPort(String appName, int port, String runtime) {
            this(appName, port, runtime, null, null, List.of(), null, null, null, null);
        }
    }

    /**
     * One native-sourced consumer derived from the pre-filled {@link AppPort}s (spec-063): the
     * native counterpart to {@link DockerConsumerData}, grouped by the internal
     * {@code contextKey} so the app-scripts collapsing to one context render as a single
     * consumer. {@code role} is {@link ConsumerRole#DATABASE} when the group fingerprints a
     * datastore (058's standalone pg/mysql/mariadb) else {@link ConsumerRole#APP};
     * {@code source} is always {@link ConsumerSource#NATIVE}. {@code contextKey} is carried for
     * internal correlation only — it never reaches a DTO (S9). {@code name} is the logical
     * {@code contextDisplay} (or the single app name when a context could not be resolved).
     */
    public record NativeConsumerData(String name, ConsumerRole role, ConsumerSource source,
                                     String contextKey, String contextDisplay, String confidence,
                                     List<String> appNames) {
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
                    String raw = recipe.getAppPortList();
                    recipes.add(new MonitorRecipe(recipe, actions, parseAppPortList(raw),
                            parseDockerConsumers(raw), parseNativeConsumers(raw)));
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
                // Native recipe: the bare item array (spec-025).
                addAppPorts(root, items);
            } else if (root.isObject()) {
                // Docker recipe (spec-061): the combined {dockerConsumers,appPortList} object.
                // A pre-061 row is a bare {dockerConsumers} object with no appPortList member ⇒
                // the empty list, never an error.
                JsonNode array = root.get("appPortList");
                if (array != null && array.isArray()) {
                    addAppPorts(array, items);
                }
            }
        } catch (JsonProcessingException e) {
            return List.of();
        }
        return items;
    }

    /**
     * Appends every persisted app-port entry of {@code array} to {@code items}, reading the rich
     * discovery-context side-data (spec-063) — {@code contextKey}/{@code contextDisplay}/{@code
     * contextScripts}/{@code sourceNote}/{@code confidence}/{@code scriptFolder} — alongside the
     * base {@code appName}/{@code port}/{@code runtime}. Absent keys default to {@code null}/empty,
     * so an old bare {@code {"appName","port","runtime"}} row and a docker-object item (which carry
     * none of the context fields) parse unchanged — 061's tolerant-reader contract holds.
     */
    private void addAppPorts(JsonNode array, List<AppPort> items) {
        for (JsonNode node : array) {
            JsonNode appName = node.get("appName");
            JsonNode port = node.get("port");
            if (appName != null && port != null) {
                items.add(new AppPort(appName.asText(), port.asInt(), text(node.get("runtime")),
                        text(node.get("contextKey")), text(node.get("contextDisplay")),
                        stringList(node.get("contextScripts")), text(node.get("sourceNote")),
                        text(node.get("confidence")), text(node.get("scriptFolder")),
                        intOrNull(node.get("managementPort"))));
            }
        }
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

    /**
     * Derives the <strong>native</strong> consumers from a recipe's stored app-port list
     * (spec-063), the native counterpart of {@link #parseDockerConsumers}: it reads the same
     * persisted value through {@link #parseAppPortList} and groups the parsed items by
     * {@code contextKey}. A null/blank/malformed value (or one with no native items) yields the
     * empty list.
     */
    private List<NativeConsumerData> parseNativeConsumers(String rawJson) {
        return nativeConsumersFrom(parseAppPortList(rawJson));
    }

    /**
     * Groups pre-filled {@link AppPort}s into native consumers (spec-063). PROCESS/SYSTEMD items
     * only — a docker-runtime item is the spec-061 double-detection link whose consumer comes from
     * the docker channel, so it is skipped here (never double-counted). Items sharing a non-null
     * {@code contextKey} collapse into one consumer named by the logical {@code contextDisplay}; an
     * item with no resolved context is its own singleton consumer named by its app name (preserving
     * the pre-063 per-app behaviour). {@code role} is {@link ConsumerRole#DATABASE} when any member
     * fingerprints a datastore, else {@link ConsumerRole#APP}; {@code source} is always
     * {@link ConsumerSource#NATIVE}.
     */
    public static List<NativeConsumerData> nativeConsumersFrom(List<AppPort> appPorts) {
        List<NativeConsumerData> out = new ArrayList<>();
        Map<String, List<AppPort>> byContext = new LinkedHashMap<>();
        for (AppPort app : appPorts) {
            if ("docker".equalsIgnoreCase(app.runtime())) {
                continue; // docker-cgroup items route to the docker channel (056/061)
            }
            if (app.contextKey() == null) {
                out.add(nativeConsumer(
                        app.contextDisplay() != null ? app.contextDisplay() : app.appName(),
                        null, app.contextDisplay(), List.of(app)));
            } else {
                byContext.computeIfAbsent(app.contextKey(), k -> new ArrayList<>()).add(app);
            }
        }
        for (Map.Entry<String, List<AppPort>> entry : byContext.entrySet()) {
            List<AppPort> group = entry.getValue();
            String display = group.get(0).contextDisplay();
            String name = display != null ? display : group.get(0).appName();
            out.add(nativeConsumer(name, entry.getKey(), display, group));
        }
        return out;
    }

    /** Builds one native consumer from a context group: role from the datastore fingerprint. */
    private static NativeConsumerData nativeConsumer(String name, String contextKey, String display,
                                                     List<AppPort> group) {
        List<String> appNames = new ArrayList<>();
        String confidence = null;
        boolean datastore = false;
        for (AppPort app : group) {
            if (!appNames.contains(app.appName())) {
                appNames.add(app.appName());
            }
            if (app.confidence() != null && (confidence == null || "high".equalsIgnoreCase(app.confidence()))) {
                confidence = app.confidence();
            }
            if (isDatastoreName(app.appName())) {
                datastore = true;
            }
        }
        ConsumerRole role = datastore ? ConsumerRole.DATABASE : ConsumerRole.APP;
        return new NativeConsumerData(name, role, ConsumerSource.NATIVE, contextKey, display,
                confidence, appNames);
    }

    /**
     * The datastore engine tokens a native fingerprint marks as a {@link ConsumerRole#DATABASE}
     * (spec-063). Mirrors {@code DatastoreImages}' engine set plus the native daemon spellings
     * ({@code postmaster}/{@code mysqld}/{@code mariadbd}) the listening sweep reports as the app
     * name; nginx and other non-datastore common services are deliberately absent.
     */
    private static final Set<String> DATASTORE_TOKENS = Set.of(
            "postgres", "postgresql", "postmaster", "mysql", "mysqld", "mariadb", "mariadbd",
            "mongo", "mongodb", "redis", "valkey", "keydb", "memcached", "cassandra", "scylladb",
            "elasticsearch", "opensearch", "clickhouse", "cockroachdb", "cockroach", "influxdb",
            "timescaledb", "couchdb", "couchbase", "neo4j", "mssql", "sqlserver");

    /** Whether {@code appName} names a known datastore engine (058's standalone pg/mysql/mariadb). */
    public static boolean isDatastoreName(String appName) {
        if (appName == null) {
            return false;
        }
        String lower = appName.toLowerCase(Locale.ROOT);
        for (String token : DATASTORE_TOKENS) {
            if (lower.contains(token)) {
                return true;
            }
        }
        return false;
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

    /** A tolerant int read: {@code null} for a missing/null/non-integer value (spec-073). */
    private static Integer intOrNull(JsonNode node) {
        return node == null || node.isNull() || !node.canConvertToInt() ? null : node.asInt();
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
