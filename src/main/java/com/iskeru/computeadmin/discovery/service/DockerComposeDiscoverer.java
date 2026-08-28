package com.iskeru.computeadmin.discovery.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iskeru.computeadmin.discovery.AppPortItem;
import com.iskeru.computeadmin.discovery.DockerConsumer;
import com.iskeru.computeadmin.discovery.DockerConsumer.DockerService;
import com.iskeru.computeadmin.discovery.ProposedAction;
import com.iskeru.computeadmin.discovery.ProposedRecipe;
import com.iskeru.computeadmin.discovery.RecipeDiscoverer;
import com.iskeru.computeadmin.discovery.model.DiscovererFamily;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.monitor.model.Bucket;
import com.iskeru.computeadmin.monitor.model.ConsumerRole;
import com.iskeru.computeadmin.monitor.model.Dedication;
import com.iskeru.computeadmin.ssh.SshExecutor;
import com.iskeru.computeadmin.ssh.SshTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.iskeru.computeadmin.discovery.Proposals.literal;

/**
 * Discovers docker <strong>compose projects</strong> and proposes one {@code MONITOR}
 * recipe per project (concern-030, spec-033). Where the host-socket chain the native
 * app-monitor (spec-025) walks breaks down for containers — a published port owned by
 * {@code docker-proxy}, portless workers and datastores with no host socket, a
 * root-owned proxy PID the S5 login user can't see — docker's own labels are the way
 * in: containers are grouped by the {@code com.docker.compose.project} label (the
 * project name <em>is</em> the {@code appName}, spec-022 convention), no host port
 * required.
 *
 * <p><strong>Classification (concern-030 option B).</strong> Each container's image is
 * matched against {@link DatastoreImages}. A project with a non-datastore (app) service
 * is a single {@link ConsumerRole#APP} consumer whose {@code services} carry <em>all</em>
 * its containers — app and datastore — each tagged with its {@code role}; a datastore
 * service inside such a project is thereby <em>dedicated</em> to it (spec-038), surfaced
 * by the Databases lens rather than as its own top-level consumer. A datastore <em>not</em>
 * in any project (a bare {@code docker run redis}) is a {@link Dedication#SHARED} consumer
 * (no single owner; {@code usedBy} best-effort, left empty in v1). Everything else docker
 * is routed to the {@link Bucket#DOCKER} remainder. These land as the spec-032 consumer
 * labels ({@code source = DOCKER}, {@code role}, {@code dedication}) the monitor read
 * surfaces; the native↔docker dedup keys on {@code appName} so a springboot app that is
 * both framework-classified and compose-labelled shows once, docker-sourced.
 *
 * <p><strong>Read-only, fixed probes.</strong> Enumeration runs {@code command -v docker},
 * {@code docker ps --format '{{json .}}'} and — since spec-061 — one batched
 * {@code docker inspect --format '{{json .}}' <id>…} to enrich each container with its DNAT
 * published ports, mounts, image and data-dir env (regex-validated ids are the only bound input);
 * the proposed checks are the fixed, param-free reads {@code docker stats --no-stream},
 * {@code docker ps -s} and {@code docker system df -v} (RAM/CPU + disk, parsed
 * client-side). It never issues a mutating command and never approves — every action
 * lands {@code PENDING_APPROVAL} through the unchanged gate; there is no new
 * {@code RecipeType}.
 *
 * <p><strong>Enrichment (spec-061).</strong> Each container also contributes 056's
 * {@link AppPortItem} shape onto the recipe's {@code appPortList}: one item per published host
 * port (or a sentinel {@code port = 0} portless item), fingerprinted by image tag
 * ({@link ServiceCatalog#fingerprintByImage}), given synthetic {@code compose:<project>} /
 * {@code container:<name>} context membership, and — for a fingerprinted-DB container — a
 * Mounts-translated host data path in {@code scriptFolder}. This rides the same un-audited
 * {@code app_port_list} column as the consumers, serialised together as one combined object.
 *
 * <p><strong>Enablement (spec-035).</strong> This discoverer belongs to the
 * {@link DiscovererFamily#DOCKER} family, which is <em>default-off</em> because the
 * docker socket is root-equivalent. {@link DiscoveryService} runs it only when docker
 * discovery is enabled <em>for that machine</em>, so it is never probed speculatively —
 * superseding the interim {@code ca.discovery.docker.enabled} global flag it shipped with.
 *
 * <p>spec-033; per-machine enablement supersedes the interim flag in spec-035.
 */
@Component
public class DockerComposeDiscoverer implements RecipeDiscoverer {

    private static final Logger log = LoggerFactory.getLogger(DockerComposeDiscoverer.class);

    private static final String PROJECT_LABEL = "com.docker.compose.project";
    private static final String SERVICE_LABEL = "com.docker.compose.service";
    private static final String WORKING_DIR_LABEL = "com.docker.compose.project.working_dir";

    /** A container id is 12–64 lowercase hex; validated before it enters the inspect argv (S4). */
    private static final Pattern CONTAINER_ID = Pattern.compile("[0-9a-f]{12,64}");

    /**
     * The <strong>only</strong> {@code Config.Env} keys read from an inspect document (spec-061):
     * the datastore data-dir overrides the catalog verify step consults. Every other env entry —
     * {@code POSTGRES_PASSWORD} and friends — is dropped on the floor, never retained, logged or
     * serialised. Union of the catalog rows' {@code dataDirEnvVar}s.
     */
    private static final Set<String> DATA_DIR_ENV_KEYS = Set.of("PGDATA", "MYSQL_DATADIR");

    private final ObjectMapper json;

    public DockerComposeDiscoverer(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public DiscovererFamily family() {
        return DiscovererFamily.DOCKER;
    }

    @Override
    public List<ProposedRecipe> discover(Machine machine, SshExecutor ssh) {
        SshTarget target = Probes.target(machine);
        if (!Probes.commandExists(ssh, target, "docker")) {
            return List.of();
        }
        List<Container> containers = containers(ssh, target);
        if (containers.isEmpty()) {
            return List.of();
        }
        // One batched `docker inspect` over the enumerated containers (spec-061): DNAT
        // published ports, mounts, image + data-dir env, keyed back by container name.
        Map<String, Inspected> inspected = inspect(ssh, target, containers);

        // Partition by the compose-project label; project-less containers are standalone.
        Map<String, List<Container>> byProject = new LinkedHashMap<>();
        List<Container> standalone = new ArrayList<>();
        for (Container c : containers) {
            if (c.project() != null && !c.project().isBlank()) {
                byProject.computeIfAbsent(c.project(), p -> new ArrayList<>()).add(c);
            } else {
                standalone.add(c);
            }
        }

        List<ProposedRecipe> proposals = new ArrayList<>();
        for (Map.Entry<String, List<Container>> entry : byProject.entrySet()) {
            proposals.add(projectRecipe(entry.getKey(), entry.getValue(), inspected));
        }
        List<Container> leftover = new ArrayList<>();
        for (Container c : standalone) {
            if (DatastoreImages.isDatastore(c.image())) {
                proposals.add(standaloneDatastoreRecipe(c, inspected));
            } else {
                leftover.add(c);
            }
        }
        if (!leftover.isEmpty()) {
            proposals.add(bucketRecipe(leftover, inspected));
        }
        return proposals;
    }

    // --- proposal assembly --------------------------------------------------

    /**
     * One compose project → a MONITOR recipe carrying a <strong>single</strong> consumer
     * (spec-038). An app project is ONE {@link ConsumerRole#APP} consumer whose {@code
     * services} list carries <em>all</em> of its containers — app services and datastore
     * services alike — each tagged with its {@code role}. A project's dedicated datastore
     * is thus a {@code role=DATABASE} <em>service inside its project</em> (dedicated to it
     * by virtue of that role + parent project), <strong>not</strong> a separate top-level
     * consumer — so the fleet Apps view shows one card per project and the Databases lens
     * derives the Dedicated band from these services (matching the design mock). A
     * datastore-only project (no app service) stays one {@link Dedication#SHARED}
     * {@link ConsumerRole#DATABASE} consumer.
     */
    private ProposedRecipe projectRecipe(String project, List<Container> members,
                                         Map<String, Inspected> inspected) {
        List<DockerService> services = new ArrayList<>();
        boolean hasApp = false;
        boolean hasDb = false;
        for (Container c : members) {
            boolean db = DatastoreImages.isDatastore(c.image());
            services.add(new DockerService(c.name(), c.image(), db ? ConsumerRole.DATABASE : ConsumerRole.APP));
            hasApp |= !db;
            hasDb |= db;
        }

        DockerConsumer consumer;
        if (hasApp) {
            // An app project: ONE consumer carrying every service (app + datastore). Each
            // role=DATABASE service is dedicated to this project (owner = the project),
            // surfaced by the Databases lens — no separate dedicated consumer (spec-038).
            consumer = new DockerConsumer(project, ConsumerRole.APP, null, null,
                    List.of(), null, services);
        } else if (hasDb) {
            // A datastore-only compose project: no owning app service, so SHARED.
            consumer = new DockerConsumer(project, ConsumerRole.DATABASE, Dedication.SHARED, null,
                    List.of(), null, services);
        } else {
            consumer = new DockerConsumer(project, ConsumerRole.OTHER, null, null,
                    List.of(), null, List.of());
        }

        // Every member's published ports become AppPortItems keyed to the project's context
        // (spec-061 Decision 2/4): contextKey = "compose:<project>", a synthetic non-path token
        // that shares one context across the project's containers (dockerized DB membership).
        String contextKey = "compose:" + project;
        String contextDisplay = workingDirOf(members, inspected, project);
        List<AppPortItem> items = new ArrayList<>();
        for (Container c : members) {
            items.addAll(itemsFor(c, contextKey, contextDisplay, inspected.get(c.name())));
        }
        return ProposedRecipe.ofDocker(project,
                "Discovered docker compose project '" + project + "'.", dockerChecks(), items,
                List.of(consumer));
    }

    /** A standalone datastore container ({@code docker run redis}) → a SHARED consumer. */
    private ProposedRecipe standaloneDatastoreRecipe(Container c, Map<String, Inspected> inspected) {
        DockerConsumer consumer = new DockerConsumer(c.name(), ConsumerRole.DATABASE, Dedication.SHARED,
                null, List.of(), null,
                List.of(new DockerService(c.name(), c.image(), ConsumerRole.DATABASE)));
        List<AppPortItem> items = itemsFor(c, "container:" + c.name(), c.name(), inspected.get(c.name()));
        return ProposedRecipe.ofDocker(c.name(),
                "Discovered standalone datastore container '" + c.name() + "'.",
                dockerChecks(), items, List.of(consumer));
    }

    /** The DOCKER remainder: containers neither compose-labelled nor a datastore (spec-032 §5). */
    private ProposedRecipe bucketRecipe(List<Container> leftover, Map<String, Inspected> inspected) {
        DockerConsumer bucket = new DockerConsumer("docker", ConsumerRole.OTHER, null, null,
                List.of(), Bucket.DOCKER, List.of());
        // Unclassified standalone containers publish DNAT ports too — 056 Decision 4 forbids
        // dropping them; each keys "container:<name>" (spec-061 Implementation).
        List<AppPortItem> items = new ArrayList<>();
        for (Container c : leftover) {
            items.addAll(itemsFor(c, "container:" + c.name(), c.name(), inspected.get(c.name())));
        }
        return ProposedRecipe.ofDocker("docker containers",
                "Discovered unclassified docker containers (the DOCKER bucket).",
                dockerChecks(), items, List.of(bucket));
    }

    /**
     * The {@link AppPortItem}s one container contributes (spec-061 Decision 2/3/4): one item per
     * published host port ({@code port} = the host port), or a single sentinel {@code port = 0}
     * item when the container publishes nothing. Every item carries {@code runtime = "docker"}, the
     * synthetic {@code contextKey}/{@code contextDisplay} identity, a ports-only {@code sourceNote}
     * (never a path, S9), and the image-fingerprint {@code confidence}. Only a fingerprinted-DB
     * container sets {@code scriptFolder} (a Mounts-translated host {@code Source} path); an
     * ordinary app or nginx container leaves it {@code null} — honest absence, its bytes are the
     * docker writable-layer/volume story (spec-037), not a host {@code du}.
     */
    private List<AppPortItem> itemsFor(Container c, String contextKey, String contextDisplay,
                                       Inspected ins) {
        String image = ins != null && !ins.image().isBlank() ? ins.image() : c.image();
        ServiceCatalog.Service service = ServiceCatalog.fingerprintByImage(image);
        String confidence = null;
        String scriptFolder = null;
        if (service != null) {
            Set<Integer> internal = ins == null ? Set.of() : ins.internalPorts();
            // Two agreeing signals (image AND the catalog port among the container's internal
            // ports) ⇒ high; image alone ⇒ low (spec-061 Decision 3, mirroring 056 Decision 5).
            confidence = internal.contains(service.defaultPort()) ? "high" : "low";
            if (service.dataDirEnvVar() != null) {
                // A fingerprinted-DB row: verify the container-side data dir from the whitelisted
                // env override (else the catalog default) then translate it through Mounts[] to a
                // real host Source path — the seam 057's volume-du keys on (Decision 4).
                String containerDataDir = ins != null && ins.env().containsKey(service.dataDirEnvVar())
                        ? ins.env().get(service.dataDirEnvVar()) : service.dataDir();
                scriptFolder = ins == null ? null : translateToHost(containerDataDir, ins.mounts());
            }
        }

        List<PublishedPort> published = ins == null ? List.of() : ins.published();
        List<AppPortItem> items = new ArrayList<>();
        if (published.isEmpty()) {
            items.add(new AppPortItem(c.name(), 0, "docker", scriptFolder, contextKey,
                    contextDisplay, List.of(), noPublishedSourceNote(contextKey), confidence));
        } else {
            for (PublishedPort p : published) {
                items.add(new AppPortItem(c.name(), p.hostPort(), "docker", scriptFolder, contextKey,
                        contextDisplay, List.of(), publishedSourceNote(contextKey, p), confidence));
            }
        }
        return items;
    }

    /**
     * The fixed, param-free, read-only docker metric checks every project recipe carries.
     * RAM/CPU from {@code docker stats --no-stream} (cgroup); disk from the writable layer
     * ({@code docker ps -s}) plus named volumes ({@code docker system df -v}). All emit
     * {@code {{json .}}} where the CLI supports it and are parsed client-side (spec-023/025
     * degrade-to-raw). No bound param ⇒ trivially S4-safe; still {@code PENDING_APPROVAL}.
     */
    private List<ProposedAction> dockerChecks() {
        return List.of(
                new ProposedAction("docker stats",
                        "Per-container CPU%/memory from 'docker stats --no-stream' (cgroup). Read-only.", false,
                        List.of(literal("docker"), literal("stats"), literal("--no-stream"),
                                literal("--format"), literal("{{json .}}")),
                        List.of()),
                new ProposedAction("docker disk",
                        "Per-container writable-layer + image size from 'docker ps -s'. Read-only.", false,
                        List.of(literal("docker"), literal("ps"), literal("-s"),
                                literal("--format"), literal("{{json .}}")),
                        List.of()),
                new ProposedAction("docker volumes",
                        "Named-volume sizes from 'docker system df -v'. Read-only.", false,
                        List.of(literal("docker"), literal("system"), literal("df"), literal("-v")),
                        List.of()));
    }

    // --- probing / parsing --------------------------------------------------

    /** Every running container as {@code (id, name, image, project, service)} via {@code docker ps}. */
    private List<Container> containers(SshExecutor ssh, SshTarget target) {
        List<Container> out = new ArrayList<>();
        for (String line : Probes.lines(ssh, target,
                List.of("docker", "ps", "--format", "{{json .}}"))) {
            try {
                JsonNode node = json.readTree(line);
                String name = firstName(node.path("Names").asText(""));
                if (name.isBlank()) {
                    continue;
                }
                String id = node.path("ID").asText("");
                String image = node.path("Image").asText("");
                Map<String, String> labels = parseLabels(node.path("Labels").asText(""));
                out.add(new Container(id, name, image,
                        labels.get(PROJECT_LABEL), labels.get(SERVICE_LABEL)));
            } catch (JsonProcessingException e) {
                // A malformed `docker ps` line degrades to skipped, never a failed probe.
            }
        }
        return out;
    }

    /**
     * One batched {@code docker inspect --format '{{json .}}' <id>…} over the enumerated
     * containers (spec-061 Decision 1), parsed to {@link Inspected} keyed by container name. Every
     * id is regex-validated ({@link #CONTAINER_ID}) before it enters the argv — the only bound
     * input, so the S4 escaping guarantee holds. A malformed inspect line degrades to a skipped
     * container (its container simply has no enrichment), <strong>never</strong> a failed probe and
     * <strong>never</strong> logged with the raw line — that line carries the full {@code
     * Config.Env}, secrets included.
     */
    private Map<String, Inspected> inspect(SshExecutor ssh, SshTarget target, List<Container> containers) {
        List<String> ids = new ArrayList<>();
        for (Container c : containers) {
            if (c.id() != null && CONTAINER_ID.matcher(c.id()).matches()) {
                ids.add(c.id());
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<String> argv = new ArrayList<>(List.of("docker", "inspect", "--format", "{{json .}}"));
        argv.addAll(ids);
        Map<String, Inspected> byName = new LinkedHashMap<>();
        for (String line : Probes.lines(ssh, target, argv)) {
            try {
                Inspected ins = parseInspect(json.readTree(line));
                if (ins != null) {
                    byName.put(ins.name(), ins);
                }
            } catch (JsonProcessingException e) {
                // Skip this container's enrichment. The raw line is NEVER placed in the log or the
                // exception — it holds Config.Env secrets (spec-061 no-secrets rule).
                log.debug("Skipping an unparseable docker inspect line");
            }
        }
        return byName;
    }

    /**
     * One inspect document → {@link Inspected}: the container name (correlation key), image,
     * compose {@code working_dir} label, whitelisted data-dir env values, mounts, and published +
     * internal ports. Only {@link #DATA_DIR_ENV_KEYS} are read from {@code Config.Env}; every other
     * entry (passwords, tokens) is dropped and never retained. {@code null} when the document has
     * no usable name.
     */
    private Inspected parseInspect(JsonNode doc) {
        String name = stripLeadingSlash(doc.path("Name").asText(""));
        if (name.isBlank()) {
            return null;
        }
        JsonNode config = doc.path("Config");
        String image = config.path("Image").asText("");
        String workingDir = blankToNull(config.path("Labels").path(WORKING_DIR_LABEL).asText(""));

        // Whitelisted-key env scan: keep ONLY the datastore data-dir vars, values are paths.
        Map<String, String> env = new LinkedHashMap<>();
        for (JsonNode entry : config.path("Env")) {
            String kv = entry.asText("");
            int eq = kv.indexOf('=');
            if (eq > 0) {
                String key = kv.substring(0, eq);
                if (DATA_DIR_ENV_KEYS.contains(key)) {
                    env.put(key, kv.substring(eq + 1));
                }
            }
        }

        List<Mount> mounts = new ArrayList<>();
        for (JsonNode m : doc.path("Mounts")) {
            String source = blankToNull(m.path("Source").asText(""));
            String destination = blankToNull(m.path("Destination").asText(""));
            if (source != null && destination != null) {
                mounts.add(new Mount(m.path("Type").asText(""), source, destination));
            }
        }

        List<PublishedPort> published = new ArrayList<>();
        Set<Integer> internal = new LinkedHashSet<>();
        parsePorts(doc.path("NetworkSettings").path("Ports"), published, internal);
        if (published.isEmpty()) {
            // Fallback to HostConfig.PortBindings for the DNAT truth (spec-061 Decision 1).
            parsePorts(doc.path("HostConfig").path("PortBindings"), published, internal);
        }
        return new Inspected(name, image, workingDir, env, mounts, published, internal);
    }

    /**
     * Parses a docker ports map ({@code {"5432/tcp":[{"HostPort":"5432"},…] | null}}) — the shape
     * {@code NetworkSettings.Ports} and {@code HostConfig.PortBindings} share. Every map key is an
     * internal container port (the fingerprint port signal); every non-null {@code HostPort} is a
     * published DNAT mapping.
     */
    private static void parsePorts(JsonNode portsNode, List<PublishedPort> published, Set<Integer> internal) {
        if (portsNode == null || !portsNode.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = portsNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String key = field.getKey();
            int slash = key.indexOf('/');
            String proto = slash >= 0 ? key.substring(slash + 1) : "tcp";
            int containerPort = parsePort(slash >= 0 ? key.substring(0, slash) : key);
            if (containerPort > 0) {
                internal.add(containerPort);
            }
            JsonNode bindings = field.getValue();
            if (bindings != null && bindings.isArray()) {
                for (JsonNode binding : bindings) {
                    int hostPort = parsePort(binding.path("HostPort").asText(""));
                    if (hostPort > 0) {
                        published.add(new PublishedPort(hostPort, containerPort, proto));
                    }
                }
            }
        }
    }

    /**
     * Translates a container-side data dir to its host path through {@code Mounts[]} (spec-061
     * Decision 3): the longest {@code Destination} prefix wins and its host {@code Source} — a
     * bind's source, or a named volume's {@code _data} mountpoint — carries the remaining path
     * tail. {@code null} when no mount covers the dir (nothing du-able to claim — honest absence).
     */
    private static String translateToHost(String containerDir, List<Mount> mounts) {
        if (containerDir == null || containerDir.isBlank()) {
            return null;
        }
        Mount best = null;
        for (Mount m : mounts) {
            if (containerDir.equals(m.destination())
                    || containerDir.startsWith(m.destination().endsWith("/")
                            ? m.destination() : m.destination() + "/")) {
                if (best == null || m.destination().length() > best.destination().length()) {
                    best = m;
                }
            }
        }
        if (best == null) {
            return null;
        }
        return best.source() + containerDir.substring(best.destination().length());
    }

    /** The compose {@code working_dir} label of any inspected member (Decision 4), else the project name. */
    private static String workingDirOf(List<Container> members, Map<String, Inspected> inspected, String project) {
        for (Container c : members) {
            Inspected ins = inspected.get(c.name());
            if (ins != null && ins.workingDir() != null && !ins.workingDir().isBlank()) {
                return ins.workingDir();
            }
        }
        return project;
    }

    private static String branchLabel(String contextKey) {
        return contextKey.startsWith("compose:") ? "compose project" : "standalone container";
    }

    /** A ports-only provenance string — protocol and mapping, never a path (S9). */
    private static String publishedSourceNote(String contextKey, PublishedPort p) {
        return branchLabel(contextKey) + " · discovered via docker · published :"
                + p.hostPort() + "→" + p.containerPort() + "/" + p.proto();
    }

    private static String noPublishedSourceNote(String contextKey) {
        return branchLabel(contextKey) + " · discovered via docker · no published port";
    }

    private static int parsePort(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String stripLeadingSlash(String name) {
        String trimmed = name == null ? "" : name.trim();
        return trimmed.startsWith("/") ? trimmed.substring(1) : trimmed;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** {@code docker ps} joins multiple names with a comma; the first is the canonical one. */
    private static String firstName(String names) {
        int comma = names.indexOf(',');
        return (comma >= 0 ? names.substring(0, comma) : names).trim();
    }

    /** The {@code Labels} field is a flat {@code k=v,k2=v2} string (label values carry no comma). */
    private static Map<String, String> parseLabels(String labels) {
        Map<String, String> out = new LinkedHashMap<>();
        if (labels == null || labels.isBlank()) {
            return out;
        }
        for (String pair : labels.split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        return out;
    }

    /** One running container's identity and its compose labels (null when not compose-managed). */
    private record Container(String id, String name, String image, String project, String service) {
    }

    /**
     * One container's {@code docker inspect} enrichment (spec-061): the correlation {@code name},
     * {@code image}, compose {@code workingDir} label, the whitelisted data-dir {@code env} values
     * (paths only, no secrets), {@code mounts}, {@code published} DNAT ports and {@code
     * internalPorts} (the fingerprint port signal).
     */
    private record Inspected(String name, String image, String workingDir, Map<String, String> env,
                             List<Mount> mounts, List<PublishedPort> published, Set<Integer> internalPorts) {
    }

    /** A mount's {@code (type, host source, container destination)} — the data-dir translation input. */
    private record Mount(String type, String source, String destination) {
    }

    /** A published DNAT mapping: the {@code hostPort} ↔ {@code containerPort} over {@code proto}. */
    private record PublishedPort(int hostPort, int containerPort, String proto) {
    }
}
