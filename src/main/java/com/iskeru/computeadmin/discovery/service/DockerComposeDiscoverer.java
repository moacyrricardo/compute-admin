package com.iskeru.computeadmin.discovery.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iskeru.computeadmin.discovery.DockerConsumer;
import com.iskeru.computeadmin.discovery.DockerConsumer.DockerService;
import com.iskeru.computeadmin.discovery.ProposedAction;
import com.iskeru.computeadmin.discovery.ProposedRecipe;
import com.iskeru.computeadmin.discovery.RecipeDiscoverer;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.monitor.model.Bucket;
import com.iskeru.computeadmin.monitor.model.ConsumerRole;
import com.iskeru.computeadmin.monitor.model.Dedication;
import com.iskeru.computeadmin.ssh.SshExecutor;
import com.iskeru.computeadmin.ssh.SshTarget;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * is an {@link ConsumerRole#APP}; its own datastore services are {@link
 * Dedication#DEDICATED} to it ({@code owner = project}). A datastore <em>not</em> in any
 * project (a bare {@code docker run redis}) is {@link Dedication#SHARED} (no single
 * owner; {@code usedBy} best-effort, left empty in v1). Everything else docker is routed
 * to the {@link Bucket#DOCKER} remainder. These land as the spec-032 consumer labels
 * ({@code source = DOCKER}, {@code role}, {@code dedication}, {@code owner}) the monitor
 * read surfaces; the native↔docker dedup keys on {@code appName} so a springboot app that
 * is both framework-classified and compose-labelled shows once, docker-sourced.
 *
 * <p><strong>Read-only, fixed probes.</strong> Enumeration runs only
 * {@code command -v docker} and {@code docker ps --format '{{json .}}'}; the proposed
 * checks are the fixed, param-free reads {@code docker stats --no-stream},
 * {@code docker ps -s} and {@code docker system df -v} (RAM/CPU + disk, parsed
 * client-side). It never issues a mutating command and never approves — every action
 * lands {@code PENDING_APPROVAL} through the unchanged gate; there is no new
 * {@code RecipeType}.
 *
 * <p><strong>Interim enablement guard (spec-033 → spec-035).</strong> Until spec-035
 * builds the per-machine docker-discovery enablement UX, this discoverer is gated behind
 * {@code ca.discovery.docker.enabled} (default {@code false}). While disabled it is a
 * strict no-op — it proposes nothing and <em>never probes docker</em>, so it is never run
 * speculatively on a box. spec-035 supersedes this flag with the per-machine model.
 *
 * <p>spec-033.
 */
@Component
public class DockerComposeDiscoverer implements RecipeDiscoverer {

    private static final String PROJECT_LABEL = "com.docker.compose.project";
    private static final String SERVICE_LABEL = "com.docker.compose.service";

    private final boolean enabled;
    private final ObjectMapper json;

    public DockerComposeDiscoverer(
            @Value("${ca.discovery.docker.enabled:false}") boolean enabled, ObjectMapper json) {
        this.enabled = enabled;
        this.json = json;
    }

    @Override
    public List<ProposedRecipe> discover(Machine machine, SshExecutor ssh) {
        // Interim guard (spec-035 supersedes): disabled ⇒ never touch docker at all.
        if (!enabled) {
            return List.of();
        }
        SshTarget target = Probes.target(machine);
        if (!Probes.commandExists(ssh, target, "docker")) {
            return List.of();
        }
        List<Container> containers = containers(ssh, target);
        if (containers.isEmpty()) {
            return List.of();
        }

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
            proposals.add(projectRecipe(entry.getKey(), entry.getValue()));
        }
        List<Container> leftover = new ArrayList<>();
        for (Container c : standalone) {
            if (DatastoreImages.isDatastore(c.image())) {
                proposals.add(standaloneDatastoreRecipe(c));
            } else {
                leftover.add(c);
            }
        }
        if (!leftover.isEmpty()) {
            proposals.add(bucketRecipe());
        }
        return proposals;
    }

    // --- proposal assembly --------------------------------------------------

    /** One compose project → a MONITOR recipe carrying its app + dedicated-db consumers. */
    private ProposedRecipe projectRecipe(String project, List<Container> members) {
        List<DockerService> appServices = new ArrayList<>();
        List<DockerService> dbServices = new ArrayList<>();
        for (Container c : members) {
            boolean db = DatastoreImages.isDatastore(c.image());
            DockerService svc = new DockerService(c.name(), c.image(), db ? ConsumerRole.DATABASE : ConsumerRole.APP);
            (db ? dbServices : appServices).add(svc);
        }

        List<DockerConsumer> consumers = new ArrayList<>();
        if (!appServices.isEmpty()) {
            // An app project: the app itself, plus each of its datastores DEDICATED to it.
            consumers.add(new DockerConsumer(project, ConsumerRole.APP, null, null,
                    List.of(), null, appServices));
            for (DockerService db : dbServices) {
                consumers.add(new DockerConsumer(db.name(), ConsumerRole.DATABASE, Dedication.DEDICATED,
                        project, List.of(), null, List.of(db)));
            }
        } else if (!dbServices.isEmpty()) {
            // A datastore-only compose project: no owning app service, so SHARED.
            consumers.add(new DockerConsumer(project, ConsumerRole.DATABASE, Dedication.SHARED, null,
                    List.of(), null, dbServices));
        } else {
            consumers.add(new DockerConsumer(project, ConsumerRole.OTHER, null, null,
                    List.of(), null, List.of()));
        }
        return ProposedRecipe.ofDocker(project,
                "Discovered docker compose project '" + project + "'.", dockerChecks(), consumers);
    }

    /** A standalone datastore container ({@code docker run redis}) → a SHARED consumer. */
    private ProposedRecipe standaloneDatastoreRecipe(Container c) {
        DockerConsumer consumer = new DockerConsumer(c.name(), ConsumerRole.DATABASE, Dedication.SHARED,
                null, List.of(), null,
                List.of(new DockerService(c.name(), c.image(), ConsumerRole.DATABASE)));
        return ProposedRecipe.ofDocker(c.name(),
                "Discovered standalone datastore container '" + c.name() + "'.",
                dockerChecks(), List.of(consumer));
    }

    /** The DOCKER remainder: containers neither compose-labelled nor a datastore (spec-032 §5). */
    private ProposedRecipe bucketRecipe() {
        DockerConsumer bucket = new DockerConsumer("docker", ConsumerRole.OTHER, null, null,
                List.of(), Bucket.DOCKER, List.of());
        return ProposedRecipe.ofDocker("docker containers",
                "Discovered unclassified docker containers (the DOCKER bucket).",
                dockerChecks(), List.of(bucket));
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

    /** Every running container as {@code (name, image, project, service)} via {@code docker ps}. */
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
                String image = node.path("Image").asText("");
                Map<String, String> labels = parseLabels(node.path("Labels").asText(""));
                out.add(new Container(name, image,
                        labels.get(PROJECT_LABEL), labels.get(SERVICE_LABEL)));
            } catch (JsonProcessingException e) {
                // A malformed `docker ps` line degrades to skipped, never a failed probe.
            }
        }
        return out;
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
    private record Container(String name, String image, String project, String service) {
    }
}
