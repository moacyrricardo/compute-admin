# 056 — App discovery

**Status:** todo · Linear [BOL-885](https://linear.app/iskeru/issue/BOL-885) · build branch `moacyrricardo/bol-885-cpt-056-app-discovery`. **Blocked by 055 (BOL-884).**

## Context

Concern [054](054-concern-lightweight-app-model.md) locked a purely-mechanical model of *what an
app is* (its five "Decisions taken", 2026-08-27) and the 055–060 epic graduates it into buildable
code. Spec [055](055-todo-app-model-foundations.md) fixed the **record schema** and the
**resolution seam** (`discovery/service/ContextMapper`, the `{scriptFolder, contextKey,
contextDisplay}` extension of `AppPortItem`, and the D1/D2 identity rules). **This spec owns the
Discovery stage** — the sweeps that *produce* the records `ContextMapper` maps. It reconciles with
what already runs the raw reads: `AppMonitorDiscoverer`
(`discovery/service/AppMonitorDiscoverer.java`) today does a **listening-port only** sweep
(`listeners` ~:204 `ss -ltnp`, `cmdline` :213, `runtimeOf` :219 reading `/proc/<pid>/cgroup`,
`containerName` :232, `deployDirName` :411 `readlink /proc/<pid>/cwd`) and `DockerComposeDiscoverer`
(`discovery/service/DockerComposeDiscoverer.java`) enumerates containers by compose-project label
(`docker ps --format {{json .}}`, :216) while **explicitly bypassing** the `/proc` chain because a
container's cwd is overlayfs (:30–36). Both feed the shared `RecipeDiscoverer` port
(`discovery/RecipeDiscoverer.java:36`) and orchestrator `DiscoveryService.discover`
(`discovery/service/DiscoveryService.java:144`) with its family-enablement gate
(`DiscovererFamily`, `discovery/model/DiscovererFamily.java:23`).

Three blind spots in today's discovery are what 056 closes, all named in 054's Discovery stage and
D4: (a) the port sweep sees **only** listening sockets, so workers/cron/batch apps are invisible;
(b) a **published container port** may be owned by `docker-proxy` or by nothing on the host
(iptables owns the DNAT), so `ss` cannot attribute it to a PID; (c) common services (nginx /
postgres / mysql / mariadb) are found by fixed-install probes today (`NginxDiscoverer`,
`DatabaseDiscoverer`) but not **fingerprinted** into the app map. 056 **depends on** 055's
`ContextMapper` and record fields (it calls the seam, it does not define it) and **feeds** 057
(per-context probing), 058 (standalone-DB sizing), 059 (the discovery-by-context UI), and 060 (the
end-to-end MCP gate audit). The approval gate (spec-004/015) is **not** touched; every probe here
is read-only, no-sudo by default, and nothing new on the MCP surface can approve.

## Decision

The following are decided (they restate 054's locked decisions prescriptively; do not reopen):

1. **Two unioned sweeps produce the app map (054 Discovery).** The set of discovered apps on a
   host is the **union** of (a) the **listening-port** sweep — `ss -ltnp` (fallback
   `/proc/net/tcp{,6}` + fd-scan) → PID → `exe`/`cwd`/`cmdline` — and (b) the **Docker** sweep —
   `docker ps`/`docker inspect` grouped by compose project. Each discovered app emits exactly one
   discovery record — carrying, alongside 055's `{scriptFolder, contextKey, contextDisplay}` and
   the existing `{runtime, port, confidence}`, a **`sourceNote`**: a short human string naming the
   sweep branch that found it (e.g. "app folder · discovered via port :8080 + systemd unit",
   "compose project · discovered via docker", "declared app · cron-launched · no port"), set per
   branch below and carried as un-audited `app_port_list` side-data for 059 to render. 055's
   `ContextMapper` collapses records that resolve to the same context.

2. **cgroup-first routing is mandatory (054's load-bearing rule).** For every PID, read
   `/proc/<pid>/cgroup` **before** trusting `exe`/`cwd`. A PID whose cgroup marks it
   `docker`/`kubepods`/`containerd` is a **containerized** app: its host-side `/proc/<pid>/cwd`/`exe`
   is an overlayfs path (`/var/lib/docker/overlay2/…`) and is **never** mapped as a host app-folder.
   Such a PID routes to the **Docker branch** (its context comes from `docker inspect`, not `/proc`).
   Only `PROCESS`/`SYSTEMD` runtimes feed `ContextMapper` — exactly the split 055 requires.

3. **The non-listening sweep is in scope (054 D4).** Alongside ports+docker, run three
   complementary enumerations and union their results: **`systemctl list-units --state=running`**
   (service units), **cron enumeration** (per-user and system crontabs + `/etc/cron.*`), and a
   full **`ps -eo …args`** interpreter-pattern scan (a process whose argv is
   `python|node|ruby|php|java … <script>` and that owns no listening socket). A non-listening app
   emits the **same record** as a listening one but with an **empty port list**; its script-path is
   `argv[1+]` (the interpreter's target), resolved to a context by the same `ContextMapper` seam.
   This is what makes 053's "structurally undetectable" population discoverable, per 054 D4.

4. **DNAT / docker-proxy ports resolve through the Docker branch, never `/proc`.** Any port that
   `docker ps`/`inspect` reports as published but that `ss` cannot attribute to a host PID (owned by
   `docker-proxy`, or by iptables with no host process) is attributed to the **container** from the
   Docker sweep — it must **not** be dropped, and it must **not** be mapped as a native app via a
   `docker-proxy` PID's `/proc` path. Published-port truth comes from `inspect`, not the host socket.

5. **Common services are fingerprinted, not assumed (054 Docker/common-service layer).** nginx,
   postgres, mysql, and mariadb are identified by a **fingerprint → catalog → verify** rule:
   process/exe or image tag selects the service; a fixed default-folder catalog supplies its
   config/data/log/port; `Config.Env` overrides (`PGDATA`, `MYSQL_DATADIR`) and, for dockerized
   rows, `Mounts[]` are checked **before** trusting the catalog default. Two agreeing signals
   (process **and** port, or image **and** port) mark the fingerprint **confident**. A **dockerized**
   DB shares its compose project's context; a **standalone** DB is its own context (its *sizing* is
   058, not here).

## Implementation

### Where the sweeps live

Discovery is realised inside the existing `discovery` module behind the `RecipeDiscoverer` port —
no new module, no gate change. The listening-port and non-listening sweeps extend / sit beside
`AppMonitorDiscoverer` (`family() == APP`, `discovery/service/AppMonitorDiscoverer.java:69`); the
Docker branch extends `DockerComposeDiscoverer` (`family() == DOCKER`). `DiscoveryService.discover`
(`discovery/service/DiscoveryService.java:144`) already runs every enabled discoverer with **no open
transaction** during SSH and persists proposals in one short tx (:164) — 056 adds probe reads, not
orchestration. All new probes route through `Probes` (`discovery/service/Probes.java`): `target`
(:24), `commandExists` (:29), `lines` (:35), plus the `sh -c` fixed-script idiom
`AppMonitorDiscoverer` already uses (`PROCESS_PROBE_SCRIPT` :114) for multi-step reads.

**Family enablement.** No new `DiscovererFamily` value is required: listening + non-listening native
apps stay under `APP` (default-on, `DiscovererFamily.java:48`); the Docker branch stays under
`DOCKER` (default-off, root-equivalent socket, :33). The non-listening sweep is **part of the
`APP` discoverer's own `discover()`**, so it is gated by the same `APP` toggle and the same
per-machine `enablementService.enabledFamilies` filter (`DiscoveryService.java:150`) — zero wiring
change; `DiscoveryService`'s injected `List<RecipeDiscoverer>` picks everything up.

### The listening-port sweep (extend `AppMonitorDiscoverer`)

The existing chain already yields `(port, pid, process)` (`listeners`/`parseSs`), `cmdline`, and the
cgroup runtime. 056 adds:

- **`/proc/net/tcp{,6}` fallback.** When `ss` is absent or its `users:()` column is blank (no-sudo
  on another user's socket, a silent gap), read `/proc/net/tcp` + `/proc/net/tcp6`, filter `st=0A`
  (LISTEN), decode the hex `local_address` port, and join the socket **inode** to a PID by scanning
  `/proc/<pid>/fd` symlinks (`socket:[<inode>]`). This recovers the port inventory unprivileged even
  when the `ss` join is denied. A denied join yields a listener with a **null PID** labelled
  `confidence=low` (054 D3 posture) — **never** read a blank users-column as "no process".
- **`exe` as an app-script signal.** Add `readlink /proc/<pid>/exe` (already needed by 055 for the
  physical realpath). For a compiled binary the exe **is** the app-script; for an interpreter
  (`python3 /opt/app/server.py`) the real app-script is **`argv[1+]` from `cmdline`**, not `exe` —
  the same interpreter rule the non-listening scan uses.
- **cgroup gate before mapping (Decision 2).** Reuse `runtimeOf` (:219) / `containerName` (:232):
  `DOCKER`/`kubepods`/`containerd` → route to the Docker branch, do **not** call `ContextMapper` on
  a `/proc` path. `PROCESS`/`SYSTEMD` → resolve `scriptPath` (logical `cwd`/`exe`) and
  `realScriptPath` (`readlink -f`), then hand both to `ContextMapper.resolveContext(...)` (055 seam)
  to stamp `scriptFolder`/`contextKey`/`contextDisplay` on the `AppPortItem`.

### The non-listening sweep (new, inside the `APP` discoverer)

Three enumerations, unioned into the same `AppPortItem` shape with a **sentinel `port = 0`**
(`AppPortItem.port` is a primitive `int`, so 0 marks "no listening port" — there is no nullable-port
handling to reuse; the [1,65535] run-time port validator must **skip** these non-listening items, a 057
concern), then de-duplicated against the
listening set by PID (a PID already found via a socket is not re-emitted):

- **systemd:** `systemctl list-units --type=service --state=running --no-legend --plain` →
  `MainPID` per unit (`systemctl show -p MainPID --value <unit>`), runtime `SYSTEMD`. This is the
  `systemctl(running)` union 054 D4 requires; today it exists in `SystemdDiscoverer`
  (`discovery/service/SystemdDiscoverer.java:45`) as a *lifecycle* lens but is **not** unioned into
  the app map — 056 joins it in for context mapping.
- **cron:** enumerate `crontab -l` (login user), `/etc/crontab`, and `/etc/cron.d/*` +
  `/etc/cron.{daily,hourly,weekly,monthly}/*`; extract the command's leading script path. A cron
  app has no live PID; it emits a record keyed on its **script path** (mapped by `ContextMapper`),
  runtime `PROCESS`, `confidence=low`, sentinel port. No `CronDiscoverer` overlap issue — that lens
  (`discovery/service/CronDiscoverer.java`) is a **read-only cron listing** (`crontab -l` / `ls /etc/cron.d`,
  adding/removing entries is its deferred scope); here cron entries are read only to seed context identity.
- **interpreter scan:** `ps -eo pid=,args=` → for each argv matching the fixed interpreter set
  `{python, python3, node, ruby, php, java, perl, bash, sh}` followed by a **file argument**, take
  that file as the app-script and resolve `/proc/<pid>/cwd` + `readlink -f` for the physical path.
  Skip any PID already emitted by the port sweep or whose cgroup routes it to Docker (Decision 2).

**S4 safety.** Every read is a **constant** argv or a **fixed `sh -c` script** whose body is a
source-controlled string; the only bound inputs are a validated PID (integer) or a service unit
name matched against a fixed pattern — never a free-form param, never a mutating command, never
`sudo` by default (privilege upgrade is 054 D3, deferred — see Known Gaps). The interpreter set,
wrapper set, and cron paths are compile-time constants. This preserves the S4 escaping guarantee
(ARCH.md gate point 5) and the spec-006 read-only-discoverer contract per invocation.

### The Docker branch (extend `DockerComposeDiscoverer`)

- **`docker inspect` for resolved facts (054 Docker layer).** Alongside the current
  `docker ps --format {{json .}}` project grouping (`DockerComposeDiscoverer.java:216`), add
  `docker inspect <id>` to read **`Mounts[]`** (real host paths behind container-side data dirs),
  **`NetworkSettings.Ports`** (published-port truth), and **`Config.Env`** (`PGDATA`/`MYSQL_DATADIR`
  overrides for the fingerprint catalog). "Inspect the live engine, don't parse Dockerfiles" —
  no Dockerfile/compose-source parsing; the only dependency is the Docker CLI already required by
  this discoverer.
- **DNAT / docker-proxy handling (Decision 4) — two independent per-branch behaviours, not a
  cross-discoverer join.** `AppMonitorDiscoverer` (family `APP`) and `DockerComposeDiscoverer`
  (family `DOCKER`) are separate `RecipeDiscoverer` beans invoked independently in
  `DiscoveryService.discover` (:153–158) with no shared state — neither can see the other's results.
  So: the **Docker branch** owns published-port truth from `inspect` (a container's port attributes to
  its compose-project context, keyed on the project, never the overlayfs path); the **listening
  branch** must positively **recognise and skip** a `docker-proxy` host process (its `/proc` path is
  never a native app-folder), rather than expecting the Docker branch to reconcile it. No shared seam
  is introduced.

### Common-service fingerprinting (fingerprint → catalog → verify)

A fixed, source-controlled `ServiceCatalog` (a pure helper, no suffix, `SlugGenerator`-style per
CONTRIBUTING §6) holds the Debian/Ubuntu-family default-folder rows for nginx / postgres / mysql /
mariadb (config / data / log / default-port / detect-by signal). The rule, applied in both branches:

1. **fingerprint** — match by process/exe name or docker **image tag** (`nginx*`, `postgres*`,
   `mysql*`, `mariadb*`);
2. **catalog** — look up the default folders/port for that row;
3. **verify** — override the default from `Config.Env` (`PGDATA`, `MYSQL_DATADIR`) and, for
   dockerized rows, translate the container-side data path through `Mounts[]` to the real host bytes;
   for nginx, real roots come from `nginx -T` where readable.

Two agreeing signals (process **and** port, or image **and** port) stamp the record
`confidence=high`; a single signal stamps `confidence=low` (054 D3 labelling). This *supplements*
today's fixed-install probes (`NginxDiscoverer.java:38`, `DatabaseDiscoverer.java:36`) — it does not
replace their recipe proposals; it adds the **context identity** those services get in the app map.
A dockerized DB shares its compose project's context; a standalone DB is its own context (058 sizes
it).

### MCP-surface delta and keeping GateArchTest / S9 green

- **No new MCP tool, no new run path, no gate change.** 056 adds probe reads and record fields
  behind the existing `RecipeDiscoverer` port; the only MCP-reachable entry is the unchanged
  `discover_recipes` tool (`mcp/DiscoverRecipesTool.java`), which SSHes in, runs these read-only
  probes, and persists proposals `PENDING_APPROVAL` — it never mutates the box and never approves.
  `GateArchTest` (`recipe/GateArchTest.java`) and `blueprint/BlueprintGateTest` stay green: nothing
  here references `ApprovalService` or a `*Repository` from `mcp/`, and no tool name contains
  "approve".
- **S9 stays closed (paths off MCP).** Every path this sweep resolves — `scriptFolder`,
  `contextKey`, `realScriptPath`, `Mounts[]` host paths — is **discovery side-data on the un-audited
  `app_port_list` JSON seam** (055's fields), never a value serialised to any MCP tool response. MCP
  identity remains the **accepted basename** (053 dec. 12 / spec-028 `McpMachineView` precedent);
  055's new arch test (no MCP tool emits a raw absolute path) covers the fields 056 populates. 056
  adds **no** new path-shaped MCP field and **no** LITERAL argToken carrying a resolved path — it
  only fills side-data.
- **Persistence seam + a widening migration.** Resolved records serialise through
  `DiscoveryService.persist` (:185) → `toJson` (:244) →
  `recipeService.refreshDiscoveredAppPortList(recipeId, json)` — hash-free (outside the approval hash),
  re-discovery-refreshed; reconciliation stays on the identity triple `(machine, type, name)`
  (`DiscoveryService.persist` :167). **One migration is required** (correcting an earlier "no migration"
  assumption): `app_port_list` is today `@Column(length = 4000)` — VARCHAR(4000) (`Recipe.java:85`),
  sized for a handful of listening sockets. The unioned sweeps **multiply** the item count (dozens of
  services / cron / interpreter processes) **and enlarge** each item (three 055 path fields +
  `sourceNote`), overflowing 4000 chars on a busy host so `persist(...)` throws. A **Flyway migration
  widens `app_port_list` to `@Lob`/TEXT (CLOB)** — no format change, only capacity. (Capping the
  emitted list was the alternative; rejected because it silently drops discovered apps.)

### Integration points

- **055 `ContextMapper`.** 056 is the caller: it hands `(scriptPath, realScriptPath)` from both
  sweeps to `resolveContext(...)` and writes the returned `{scriptFolder, contextKey,
  contextDisplay}` onto `AppPortItem`. 056 does **not** re-implement the wrapper/symlink rules.
- **057 probing / spec-041 `computeOther`.** The records 056 emits (including empty-port and
  dockerized ones) are the contexts 057 probes; the disk/RAM/CPU numerators 057 attaches key on
  `contextKey`. No `computeOther` edit belongs here.
- **058 standalone DB.** 056 identifies a standalone DB **as a context** and marks it; 058 sizes it.
- **059 UI.** Consumes the enriched records (empty-port apps, fingerprinted services) through the
  existing discovery API shape; no UI work here.

## Known Gaps

- **Mapping is 055, not 056.** 056 assumes `ContextMapper` and the `AppPortItem` fields exist; it
  only *produces* the `(scriptPath, realScriptPath)` inputs and *calls* the seam. The wrapper-dir
  rule (D2), the symlink key (D1), sibling-script enumeration, and the spec-015/S9 riders are 055.
- **Probing axes are 057** — PSS-RAM, Δ-rate CPU, `du`-disk, dockerized-DB sizing, and the
  single-denominator `computeOther` integration. 056 finds and identifies apps; it does not size
  them. The cadence tiers (procfs / docker / `du`) are 057's concern.
- **Standalone (non-docker) DB sizing is 058** (054 D5's deferred spec). 056 identifies a standalone
  DB context; its logical/physical sizing is out of scope. Dockerized-DB *context membership*
  (shares the compose project's context) is decided here; dockerized-DB *sizing* is 057.
- **Privilege upgrade (054 D3 "re-probe with sudo") is not here.** Every read is unconditionally
  no-sudo; the sweep *labels* degraded readings (`confidence=low`, `permission-denied`, null PID)
  but the on-demand sudo-upgrade action is deferred (057, with the probe fidelity work).
- **The verb & command contract (spec-053's surviving half) is untouched** — verbs, the closed
  vocabulary, never-in-argv for declaration params, and verb-level MCP tools are 053/060 scope. 056
  produces grouping/identity metadata only, with no run semantics.
- **mongo and non-Debian service catalogs** are near-future (054 names mongo); 056 ships the
  nginx/postgres/mysql/mariadb Debian/Ubuntu-family rows only.
- **The MCP end-to-end audit is 060** — 056 keeps its own surface consistent (no new tool, paths as
  side-data) but the cross-cutting gate/S9 verification of the whole 055–060 delta is 060's job.
