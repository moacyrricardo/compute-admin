# 055 — App-model foundations & mapping

**Status:** todo · no branch yet · no Linear (blocked for this repo; tracked as `spec-055`).

## Context

Concern [054](054-concern-lightweight-app-model.md) settled, on its own terms, *what an app
is* and *how it is keyed* — a purely mechanical **{app-script, script-folder, context}** triple
reachable over SSH — and locked five decisions (its "Decisions taken", 2026-08-27). This spec is
the **foundation** of the 055–060 epic that graduates 054 into buildable code: it fixes the
**record schema** and the **resolution seam** that maps a discovery record to an owning context.
It graduates 054's Mapping stage plus the two identity decisions (D1 symlink key, D2 wrapper-dir
rule) and the two coordination riders 054 left as real work (spec-015 pin + S9 sanitisation adopt
D1's key). It reconciles with the code that already reads the raw inputs: `AppMonitorDiscoverer`
today runs the port→PID→cmdline→cgroup→cwd chain (`discovery/service/AppMonitorDiscoverer.java`
— `listeners` ~:204, `cmdline` :213, `runtimeOf` :219, `containerName` :232, `deployDirName`
:411) but **throws the folder away**, reducing `readlink /proc/<pid>/cwd` to a basename for
name-derivation only (:417–:419); `AppPortItem` (`discovery/AppPortItem.java:19`) carries only
`{appName, port, runtime}` and no path.

055 is the base of the stack. It **depends on** nothing else in the epic and **feeds** every
sibling: 056 (the Discovery sweeps that produce the records 055 maps), 057 (per-context Probing,
which measures the context 055 defines and integrates with spec-041's `computeOther`), 058
(standalone-DB sizing, 054 D5's deferred follow-on), 059 (the discovery-by-context UI surface),
and 060 (the end-to-end MCP gate audit). This spec deliberately does **not** touch the Discovery
sweeps, the probe axes, or the verb contract (spec-053's surviving half) — only the identity
model those consume. The approval gate (spec-004/015) is **not** modified here; the only gate-
adjacent work is making 054's D1 key S9-safe, which *tightens* the existing invariant, never
weakens it.

## Decision

The following are decided (they restate 054's locked decisions prescriptively; do not reopen):

1. **The context record.** A discovery record resolves to exactly one **context** — an
   app/owner folder — via a deterministic mapping. The persisted unit gains a **script-folder**
   (the directory the app-script lives in) and a **context** (the owning folder), in addition to
   the existing `{appName, port, runtime}`. Multiple app-scripts of the same host resolve to the
   **same context** when the mapping collapses them there (e.g. `/opt/lab/app1/scripts/run.sh`
   and `/opt/lab/app1/migrate.sh` both → context `/opt/lab/app1`).

2. **The symlink identity key (054 D1).** Two contexts are the **same context** iff their
   **resolved physical path** (`realpath` / `readlink -f`) is equal — dedup is on the physical
   path. The **logical** (un-resolved) path is what the UI **displays**. When the physical path
   matches `*/releases/*` or `*/versions/*`, the identity key is **promoted to the logical path**,
   so an atomic-symlink redeploy (`current → releases/<ts>`) does not fork a new context. The key
   has **two representations that are never conflated** (see Implementation): an internal
   dedup/pin key (S9-secret) and an external MCP identity (a basename-derived, human-accepted
   label — never the raw path).

3. **The wrapper-directory rule (054 D2).** From an app-script's folder, derive the context by a
   bounded upward hop. If the script sits **directly under a wrapper directory** — name in the
   fixed set `{scripts, bin, sbin, libexec, frontend, backend, cmd, dist, build, src, app}` — the
   context is the **parent** of that wrapper; otherwise the context **is** the script-folder.
   **Single hop by default**; a **second hop** fires only when the intermediate directory is
   *also* a wrapper **and** the candidate parent carries a **marker file**
   (`.git`, `compose.yaml`/`compose.yml`/`docker-compose.yml`, or `package.json`). **Cap: 2 hops.**
   A **boundary clamp** always applies: never hop onto a top-level system root (`/opt`, `/srv`,
   `/home`, `/usr`, `/var`, `/`) — if a hop would land there, stop at the child. Git-rooted
   monorepos therefore **group at the repo root** — documented, intended behaviour.

4. **Sibling-script enumeration.** A context enumerates the **app-scripts that map to it** — the
   scripts under it that the wrapper rule collapses to the same context — so an operator/agent
   sees every lifecycle script that belongs to one app grouped under one identity. This is
   *grouping metadata only*; it confers **no** run semantics (verbs are spec-053's contract, out
   of scope here).

5. **S9-safe on the MCP surface (053 dec. 12 / spec-028 precedent).** The path-derived context
   never reaches MCP as a path. Only a **basename** may seed an app identity; it becomes identity
   only when a human/agent **accepts it at approval**; the **raw path never crosses the MCP
   boundary** in any tool response, argToken value, or context field. This is enforced
   structurally (below).

6. **The riders (054 coordination).** spec-015's pinned argv and the S9 sanitisation path
   **adopt D1's key**: the pin/argv token that enters `ActionSnapshot.hash` is the **redeploy-
   stable logical (promoted) path**, while the **physical** path drives only spec-015's
   `approvedScriptHash` byte-integrity sibling (which stays *out* of the snapshot hash).

## Implementation

### Record schema (the mapping output)

Add the resolved location to the discovery side-data record rather than to any audited/hashed
column — it rides the existing **un-audited, re-discovery-refreshable** `app_port_list` JSON
seam, so no re-approval and no schema migration is forced. Concretely:

- Extend **`AppPortItem`** (`discovery/AppPortItem.java`) with three fields:
  `scriptFolder` (logical dir of the app-script), `contextKey` (the D1 identity key — internal),
  and `contextDisplay` (the logical path to show). Keep `{appName, port, runtime}`. The record
  stays *runtime side-data, outside the approval hash* (as `ProposedRecipe.appPortList` already
  is — `discovery/ProposedRecipe.java:38`).
- Serialisation flows through the existing seam untouched: `DiscoveryService.persist`
  (`discovery/service/DiscoveryService.java:167`) → `toJson` (:244) →
  `recipeService.refreshDiscoveredAppPortList(recipeId, json)` (the call inside persist, :184).
  The three new fields serialise
  with the rest of `AppPortItem`; re-discovery overwrites them (they are not audited, per 054 D1/
  spec-040's thin-BE posture).
- **Sibling-script list.** A context carries `List<String> scriptFolder`-relative script names
  (the app-scripts collapsing to it). Model this as a `contextScripts` array on the item (or a
  sibling record referenced from `ProposedRecipe` alongside `appPortList`/`dockerConsumers`).
  It is display/grouping side-data — never argv, never hashed.

### The resolver (the resolution seam)

Introduce a pure helper (no suffix, `SlugGenerator`-style per CONTRIBUTING §6) —
`discovery/service/ContextMapper` — with two static, side-effect-free methods over paths already
in hand at the discovery site:

- `resolveContext(scriptPath, realScriptPath) → Context{key, display, scriptFolder}` — applies
  the D2 wrapper rule (single hop; marker-gated second hop; 2-hop cap; boundary clamp) and the D1
  key rule (dedup on `realScriptPath`; promote to logical under `*/releases/*` | `*/versions/*`).
- `wrapperSet()` / `markerFiles()` / `boundaryRoots()` — the fixed constant sets above.

Wire it into `AppMonitorDiscoverer.discover` at the per-listener loop (~:175–:192), beside the
existing `appName(...)` call (~:186), reusing what the chain already computes:

- **cgroup-before-cwd guard (054's load-bearing rule).** Reuse `runtimeOf` (:219) /
  `containerName` (:232): if the PID's cgroup marks it `DOCKER`/`kubepods`/`containerd`, the
  `/proc/<pid>/cwd`/`exe` path is an overlayfs path and must **not** be mapped as a host context
  (`DockerComposeDiscoverer` already bypasses the /proc chain for this reason,
  `discovery/service/DockerComposeDiscoverer.java:30–36`; Docker contexts come from
  `docker inspect`, owned by 056). Only `PROCESS`/`SYSTEMD` runtimes feed `ContextMapper`.
- **Keep full paths.** Replace `deployDirName`'s basename-only read (:411, :417–:419) with a read
  that keeps the full logical `readlink /proc/<pid>/cwd`, and add
  `readlink -f /proc/<pid>/exe` / `realpath` for `realScriptPath`. Name-derivation keeps working
  off the basename (D5/053: basename is the identity seed).

All probes stay **S4-safe**: fixed-argv, read-only, no-sudo (`discovery/service/Probes.java`).
`realpath`/`readlink -f` run as constant `sh -c` reads with the PID/path as the only bound,
validated inputs — no free-form param, no privileged escalation (privilege upgrade is 054 D3,
owned by 056/057, not this spec).

### The two representations + the S9 rider (spec-015 / spec-028 coordination)

The context key exists as **(a)** an internal dedup/pin key — the D1 physical-or-promoted-logical
path, **treated as S9-secret** — and **(b)** an external MCP identity — a basename-derived,
human-accepted label. They are never conflated:

- **Inside the approval hash (015 rider).** Where a CUSTOM action pins a script, the LITERAL
  argToken written by `ActionService` (`recipe/service/ActionService.java:155`, absolute-validated
  :127–129) and read back by `ScriptPinService.scriptPath`
  (`recipe/service/ScriptPinService.java:92–96`) must be the **redeploy-stable logical (promoted)
  path** from `ContextMapper`, **not** the physical `*/releases/<ts>/…` path. That token is
  already inside `ActionSnapshot.hash` (`recipe/service/ActionSnapshot.java:52–59`); keying it on
  the physical path would mutate the LITERAL on every redeploy → spurious re-approval (the
  "worsened" failure 053's red-team flagged). The **physical** path drives only spec-015's
  `approvedScriptHash` byte-integrity check, which stays *out* of `ActionSnapshot.canonical()` —
  preserving 015's pure-offline hash. Net: a redeploy alone does **not** re-approve; a byte swap
  (015) or an identity/verb change (053 dec. 7) still does.
- **Off the MCP surface (S9 / 028).** The internal key is **never serialised to any MCP tool
  output** — no `list_actions` argToken value that is a raw path, no context/`appRoot` field.
  Today `ListActionsTool.summarize`/`tokenView` (`mcp/ListActionsTool.java:89–90`, :96–98) echoes
  raw argToken values verbatim; for a CUSTOM action that is the absolute script path — the one
  live S9-shaped leak spec-028 left on the argv surface. This spec closes it for the context key:
  MCP surfaces the **accepted basename** only, mirroring `McpMachineView`'s host-hiding split.

### MCP-surface delta and keeping GateArchTest / S9 green

- **No new MCP tool, no new run path.** This spec adds only the resolver and the record fields;
  the reserved-`app-name` seam (`ActionService` ~:326–349, validated against
  `ParamBinder.APP_NAME_PATTERN`, `recipe/service/ParamBinder.java:42`) is where a *basename*
  identity already attaches. `GateArchTest` (`recipe/GateArchTest.java`) stays green: nothing
  here references `ApprovalService` or a `*Repository` from `mcp/`, and no tool name contains
  "approve".
- **New structural guarantee (the 028:224–227 deferred hardening) — a runtime fix plus a
  source-scan regression.** The live leak is a **runtime** one: `ListActionsTool.tokenView`
  (`mcp/ListActionsTool.java:96–98`) returns `token.getValue()`, and for a CUSTOM action that value
  is the absolute `scriptPath` LITERAL written at run time (`recipe/service/ActionService.java:155`)
  — DB data, not a source literal a scan can see. So the guarantee has two parts. **(a) The runtime
  fix:** `tokenView` must **withhold or basename-render** any path-shaped LITERAL argToken value
  (map it to the accepted basename identity, mirroring `McpMachineView`'s host-hiding split) so no
  absolute path is ever emitted. **(b) The source-scan regression test** — same style as
  `GateArchTest` — bans **re-introducing** a serializer that echoes a raw `argToken` `getValue()`
  (or exposes a context/`appRoot`/`scriptFolder` path field on an MCP DTO). The scan guards the
  *pattern*; only the runtime fix guarantees the *output*. 060 verifies both end-to-end.
- **`ParamBinder` path validator.** Only if a resolved path ever rides as a reserved (hashed)
  param — it does **not** in this spec (context rides as side-data; the pin LITERAL is the only
  path token, and it is the logical path). No new validator is required here; noted so 056/057 do
  not add one by reflex.

### Integration points

- **057 / spec-041 `computeOther`.** The context this spec defines is the unit 057 probes; the
  disk/RAM/CPU numerators 057 emits attach to `contextKey`. No `computeOther` edit belongs here.
- **056 Discovery.** 056's sweeps (ports + docker + non-listening 054 D4) produce the records
  `ContextMapper` maps; 056 calls `resolveContext` — this spec owns the mapper, 056 owns the
  sweeps.
- **059 UI.** Displays `contextDisplay` + the sibling-script list; reuses the existing
  `.filter-chips` discovery idiom (`static/app.js` `discoverySection` ~:753). No visual reskin
  here.

## Known Gaps

- **Discovery sweeps are 056**, not here — this spec assumes a discovery record (port→PID→path,
  or a docker record) already exists and only maps it. The non-listening sweep (054 D4: `systemctl`
  running + cron + `ps -eo args`) lands in 056.
- **Probing axes are 057** — PSS-RAM, Δ-rate CPU, `du`-disk, DB sizing, and the spec-041
  single-denominator integration. This spec fixes only the context those axes measure.
- **Standalone (non-docker) DB sizing is 058** (054 D5's deferred spec); dockerized-DB context
  membership is **056's** (a dockerized DB shares its compose project's context) and its *sizing*
  is 057's — 055 only fixes that such a context keys on the compose project, not the overlayfs path.
- **Privilege upgrade (054 D3 "re-probe with sudo") is not here** — every read in this spec is
  unconditionally no-sudo. Degrade-and-label labelling is 056's; the on-demand sudo-upgrade action
  is 057's.
- **The verb & command contract (spec-053's surviving half) is untouched** — verbs, the closed
  vocabulary, the never-in-argv invariant for declaration params, and verb-level MCP tools are
  053/060 scope. This spec only guarantees the context **path** stays out of argv and off MCP; it
  does not model run semantics.
- **Docker host-path resolution via `docker inspect`** is named as 056's mechanism; this spec
  fixes only that a dockerized context keys on its compose project, not the overlayfs path.
