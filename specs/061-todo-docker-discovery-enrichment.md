# 061 — Docker-branch discovery enrichment

**Status:** todo · Linear [BOL-890](https://linear.app/iskeru/issue/BOL-890) · build branch `moacyrricardo/bol-890-cpt-061-docker-discovery-enrichment`. **Blocked by 056 (BOL-885).**

## Context

Spec [056](056-done-app-discovery.md) shipped the app-map Discovery stage **native-only**: its
Implementation Notes (item 1 of the deferral list) explicitly deferred the **docker half of
Decisions 4 & 5** — `docker inspect` enrichment, image-tag fingerprinting, DNAT published-port
truth, and dockerized-DB context membership — and left `DockerComposeDiscoverer` untouched. The
asymmetry is concrete:

- The **native branch** emits enriched `AppPortItem`s — nine components with 055's context fields
  plus 056's `sourceNote`/`confidence` (`discovery/AppPortItem.java:51–53`) — and fingerprints
  common services via `ServiceCatalog.fingerprintByProcess`
  (`discovery/service/ServiceCatalog.java:54`), env-verifying the data dir from
  `/proc/<pid>/environ` (`AppMonitorDiscoverer.java:711`, :724). The **Docker branch** still runs
  only `docker ps --format '{{json .}}'` (`DockerComposeDiscoverer.java:216–217`) and emits
  consumer classification with **no ports, no mounts, no env, no fingerprint, no context fields**
  — `ProposedRecipe.ofDocker` hard-codes an empty `appPortList` (`discovery/ProposedRecipe.java:46`).
- 056 Decision 4's **native half** shipped: the listening sweep positively skips `docker-proxy`
  listeners (`AppMonitorDiscoverer.java:206`, `isDockerProxy` :932) precisely because "published-port
  truth belongs to the Docker branch (docker inspect)". The Docker branch that is supposed to *own*
  that truth never reads it — a published container port is currently attributed to **nothing**.
- 055 fixed that a dockerized context "keys on the compose project, not the overlayfs path"
  (055 Known Gaps), and the listening sweep dutifully passes `context = null` for a `DOCKER`-cgroup
  PID (`AppMonitorDiscoverer.java:224–225`) — but nothing ever fills that context in. A dockerized
  DB today is a `role=DATABASE` service inside its project consumer
  (`DockerComposeDiscoverer.java:143`) with no context identity at all.

This spec graduates exactly that deferred slice. It **depends on** 056 (the record shape, the
`sourceNote`/`confidence` fields, the fingerprint→catalog→verify rule) and **feeds** 057 (the
dockerized-DB/volume sizing numerators key on the contexts and host paths resolved here) and 059
(the UI renders the enriched items). The approval gate is untouched; every new read is a read-only
probe on the docker socket already gated by the `DOCKER` family opt-in.

## Decision

1. **Enrich via `docker inspect` on the live engine — zero new dependencies.** After the existing
   `docker ps` enumeration, the Docker branch runs one batched
   `docker inspect --format '{{json .}}' <id>…` over the container IDs `docker ps` returned
   (each ID validated against `[0-9a-f]{12,64}` before it enters the argv). From each inspect
   document it reads exactly four things: **`Mounts[]`** (bind sources + named-volume names and
   their container-side destinations), **`NetworkSettings.Ports`** (fallback
   `HostConfig.PortBindings`) — the DNAT published-port truth: host-published port ↔
   container-internal port, resolving the docker-proxy blind spot the native branch skips —
   **`Config.Env`** (whitelisted keys only, see Implementation) and **`Config.Image`** +
   `Config.Labels` (the compose `working_dir` label). Compose source files and Dockerfiles are
   **never** parsed — inspect the engine, not the declaration (056's "inspect the live engine"
   rule, restated).

2. **The Docker branch emits 056's `AppPortItem` shape.** One item per **published host port** per
   container (`appName` = container name, `port` = the host-published port, `runtime = "docker"`);
   a portless container emits one item with 056's sentinel `port = 0`. Every item carries a
   `sourceNote` naming the branch and the port mapping — e.g.
   `"compose project · discovered via docker · published :8080→80/tcp"`,
   `"standalone container · discovered via docker · no published port"` — ports and protocol only,
   **never a path** (S9). These items ride the same un-audited `app_port_list` side-data seam;
   docker recipes' checks are fixed param-free reads (`DockerComposeDiscoverer.java:193–208`), so
   no run-time fan-out ever binds these items and the `[1,65535]` validator concern does not arise.

3. **Image-tag fingerprinting mirrors the native fingerprint→catalog→verify.** `ServiceCatalog`
   gains `fingerprintByImage(imageRef)`: normalise the ref the way `DatastoreImages` does
   (strip registry host and `:tag`/`@digest`, match repository path segments —
   `DatastoreImages.java:13–17`) against the same four Debian/Ubuntu rows (`ServiceCatalog.java:41–46`).
   Verify: the catalog's `dataDirEnvVar` (`PGDATA`/`MYSQL_DATADIR`) read from `Config.Env`
   overrides the catalog default **container-side** data dir; that dir is then translated through
   `Mounts[]` (longest-prefix destination match) to the **host-side** bytes — a bind source path,
   or a named volume. Two agreeing signals — image **and** port (the catalog `defaultPort` appears
   among the container's internal ports) — stamp `confidence = "high"`; image alone stamps
   `"low"`; unmatched images stamp `null`. Same field, same semantics as 056 Decision 5.

4. **Dockerized-DB context membership: the compose project is the context.** Every item of a
   compose-project container carries `contextKey = "compose:<project>"` and `contextDisplay` = the
   `com.docker.compose.project.working_dir` label value when present, else the project name; a
   standalone container carries `contextKey = "container:<name>"`. The keys are **synthetic
   non-path tokens** — they can never collide with a native `ContextMapper` path key (those start
   with `/`), so a dockerized DB **shares its compose project's context id** rather than standing
   alone, exactly as 055/056 decided, without routing an overlayfs path through `ContextMapper`.
   For a fingerprinted DB container, the Mounts-translated host data location rides in the item's
   `scriptFolder` field (the seam 057's volume-`du` numerator will key on); it is S9-secret
   side-data like every 055 path field.

5. **No `DiscovererFamily` change.** `docker inspect` is more reads on the **same** root-equivalent
   socket the `DOCKER` family already gates default-off (`DiscovererFamily.java:33`); the
   capability decision the family models is socket access, not command count. No new family, no
   enablement wiring.

## Implementation

### `DockerComposeDiscoverer` (the only discoverer touched)

- Extend `containers()` (`DockerComposeDiscoverer.java:214`) to also capture the `ID` field of each
  `docker ps` JSON line. Add a private `inspect(ssh, target, ids)` that runs the fixed argv
  `List.of("docker", "inspect", "--format", "{{json .}}", id1, …)` via `Probes.lines`
  (`Probes.java:36`) — one exec for all containers; IDs regex-validated (`[0-9a-f]{12,64}`) before
  joining the argv; a malformed inspect line degrades to a skipped container, never a failed probe
  (same posture as `containers()`'s per-line catch, :228–230).
- Parse per container: published ports from `NetworkSettings.Ports` (entries with a non-null
  `HostPort`), falling back to `HostConfig.PortBindings`; internal ports (the map keys, for the
  fingerprint port signal); `Mounts[]` as `(type, source, destination)` triples; `Config.Image`;
  the `com.docker.compose.project.working_dir` label; and **only** the env keys the fingerprinted
  row's `dataDirEnvVar` names — `Config.Env` also carries secrets (`POSTGRES_PASSWORD`, …), so the
  raw env array is **never** retained, logged, or serialised; extraction is a whitelisted-key scan
  and the values kept are directory paths only.
- Build `AppPortItem`s per Decision 2/4 in `projectRecipe` (:137), `standaloneDatastoreRecipe`
  (:168) **and** `bucketRecipe` (:178) — unclassified standalone containers publish DNAT ports too,
  and 056 Decision 4 forbids dropping them; the bucket recipe's items key `container:<name>`.
  `confidence` comes from Decision 3; a datastore-image container inside an app project keeps its
  project `contextKey` (membership) while its `scriptFolder` carries the translated data location.

### `ServiceCatalog` + a shared image normaliser

- Add `static Service fingerprintByImage(String imageRef)` beside `fingerprintByProcess`
  (`ServiceCatalog.java:54`). Extract `DatastoreImages`' ref-normalisation (registry/tag/digest
  strip + path-segment match) into a small shared static helper so the two classes cannot drift;
  `DatastoreImages.isDatastore` (`DatastoreImages.java:35`) keeps its own token set and behaviour.
  Matching rows: `postgres|postgresql`, `mysql`, `mariadb`, `nginx` segments onto the existing ROWS.

### Record plumbing (the two-channel seam)

- `ProposedRecipe.ofDocker` (`ProposedRecipe.java:43–46`) gains an `appPortList` parameter (the
  old signature remains as a convenience delegating `List.of()`), retiring the doc claim that the
  two pre-fill channels are mutually exclusive on a docker proposal (:21–23).
- `DiscoveryService.persist` (`DiscoveryService.java:184–189`) currently writes **either** the item
  array **or** `{"dockerConsumers":[…]}` (`toDockerConsumersJson`, :254–260). Change the docker
  path to serialise **one object** `{"dockerConsumers":[…],"appPortList":[…]}` into the same
  CLOB column. Readers are tolerant by design: `MonitorService.parseDockerConsumers` reads
  `root.get("dockerConsumers")` (`monitor/service/MonitorService.java:204`) — unchanged;
  `parseAppPortList` (:166–188) requires `root.isArray()` and is extended with one branch — when
  the root is an object, read the `appPortList` array member. Old rows parse as before; re-discovery
  rewrites the column anyway.
- **No migration.** `app_port_list` has been CLOB since 056's `V15__widen_app_port_list.sql`; this
  spec adds JSON bytes, not schema. Flyway is not touched.

### S4 / S9 / gate posture

- Every probe is a fixed, source-controlled argv (`docker ps`, `docker inspect --format`), read-only,
  no-sudo, with regex-validated container IDs as the only bound input — the spec-006 read-only
  contract and the S4 escaping guarantee (ARCH.md S4 row) hold unchanged.
- No new MCP tool, no gate change: the only entry remains `discover_recipes` → proposals persisted
  `PENDING_APPROVAL`. Every path resolved here (`Mounts[]` sources, working_dir, data dirs) is
  side-data on `app_port_list`, which **no MCP tool reads** (verified in 055's Implementation
  Notes); `sourceNote` carries ports only. 055's `McpPathLeakArchTest` and `GateArchTest` stay
  green with zero edits.

### Tests

Unit-test the inspect parser (ports/mounts/env-whitelist extraction from canned inspect JSON,
including the `PortBindings` fallback and a malformed line), `fingerprintByImage` (registry-prefixed,
tag/digest-suffixed, `bitnami/postgresql` variants; mariadb never matching the mysql row), the
Mounts longest-prefix translation (bind vs named volume), and the combined-object round trip
(`persist` → `parseAppPortList` + `parseDockerConsumers` on the same value). The `ca-sshd`
container recipe in CLAUDE.md plus a compose project on the target verifies live.

## Known Gaps

- **Sizing is 057.** This spec resolves *where* a dockerized DB's bytes live (volume name / bind
  source on `scriptFolder`) and *which context owns them*; the `docker exec` logical query and the
  volume `du` numerators are 057's, and standalone (non-docker) DB sizing remains 058.
- **No native↔docker item-level dedup.** A host-network container the listening sweep already sees
  (runtime `docker`, `AppMonitorDiscoverer.java:211–212`) may also appear as a docker-branch item;
  the discoverers are independent beans by 056's explicit design (no shared seam), and the monitor
  read's existing `appName` dedup (spec-033) is the only collapse. Acceptable duplication; 059 may
  refine presentation.
- **`working_dir` is trusted as display only.** The compose label can be stale (project deployed
  from a deleted dir) or absent (`docker run`); it never becomes an identity key — the synthetic
  `compose:`/`container:` token is the key, so staleness cannot fork or merge contexts.
- **Catalog breadth unchanged.** Only the four Debian/Ubuntu rows fingerprint; mongo/redis and
  non-Debian layouts stay a near-future `ServiceCatalog` addition (056 Known Gaps). Unmatched
  datastore images still classify via `DatastoreImages` for consumer roles — they just carry
  `confidence = null` and no data-dir translation.
- **Kubernetes/containerd-only hosts** are out of scope: enrichment requires the docker CLI
  (`Probes.commandExists(…, "docker")`, `DockerComposeDiscoverer.java:86`); kubepods-cgroup PIDs
  remain routed away from `/proc` mapping by 056 but get no inspect-equivalent here.
- **056's other deferrals stay open**: the `/proc/net/tcp{,6}` + fd-inode fallback, the
  `exe`-as-app-script signal, `nginx -T` real roots, and relative interpreter-script resolution
  (Implementation Notes items 2–3) are not absorbed into this spec.
