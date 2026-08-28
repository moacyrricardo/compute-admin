# 063 — Native-consumer channel & discovery-context DTO exposure

**Status:** doing · Linear [BOL-892](https://linear.app/iskeru/issue/BOL-892) · build branch `moacyrricardo/bol-892-cpt-063-native-consumer-context-dto`. **Stacks on the integration branch (055–062).**

## Context

The app-model epic (055 foundations · 056 discovery · 057 footprint · 061 docker-enrich · 062
proc/net) built a **rich discovery backend**: every discovered app is an `AppPortItem`
(`discovery/AppPortItem.java:51`) with nine components —
`(appName, port, runtime, scriptFolder, contextKey, contextDisplay, contextScripts, sourceNote,
confidence)`. But 055 deliberately kept those as **un-audited side-data** on the recipe's
`app_port_list` CLOB, noting that downstream "reads only `appName`/`port` from the JSON tree." The
seam that carries the richness through **persist → parse → DTO → consumer** to the view was never
built. Two shipped specs hit that wall from opposite ends:

- **059 (UI)** can't build its context-grouped discovery surfaces: the client DTO strips the fields.
  `MonitorService.AppPort` is `record AppPort(String appName, int port, String runtime)`
  (`monitor/service/MonitorService.java:78`) and `MonitorDtos.AppPortView` mirrors it
  (`monitor/api/MonitorDtos.java:392`, its `of(AppPort)` maps only the three). `RecipeDtos.RecipeView`
  (`recipe/api/RecipeDtos.java:58`) carries no `appPortList` at all. 059 shipped only the
  client-computed confidence badges and deferred Surface 1 "blocked on a sibling read-only spec".
- **058 (standalone-DB)** built the size probes but its **native `DATABASE` consumer has no channel**.
  Only `MonitorService.DockerConsumerData(name, role, dedication, owner, usedBy, bucket, services)`
  (`MonitorService.java:88`) derives consumers, via `parseDockerConsumers` (`:211`). Nothing derives a
  **native** consumer from `AppPortItem`s.

The model already **anticipated** this seam and left it half-wired: `ConsumerSource` has both
`DOCKER` **and** `NATIVE` (`monitor/model/ConsumerSource.java`), and the view record
`MonitorDtos.MonitorConsumerView(id, name, ConsumerRole role, ConsumerSource source, …)` (`:265`)
already carries `role` + `source` — but the only producer is the docker path. 063 fills the gap.

This spec is **foundational and read-only** — no new probe, no discovery change, no MCP tool, no
migration, no gate change. It unblocks a **059-followup** (context-grouped surfaces) and completes
**058**'s DB-consumer surfacing.

## Decision

Locked via the 063 decision surface (5 forks, all recommended):

1. **One foundational spec (this one)** — a single coherent seam: a native-consumer channel **plus**
   the rich-field DTO exposure. Not split, not folded into 059, not parked. (D1)
2. **A native-consumer channel mirroring `DockerConsumerData`.** A new
   `NativeConsumerData(name, role, source, contextKey, contextDisplay, confidence, appNames)` is
   **derived from the persisted `AppPortItem`s** — grouped by `contextKey`, `role` from the record's
   fingerprint/`confidence` (a fingerprinted datastore ⇒ `DATABASE`, else `APP`), `source = NATIVE`.
   It feeds the **existing** `MonitorConsumerView` exactly as the docker path does. `AppPortItem`
   stays lean — no `role`/`source` field is added to the discovery record (classification is a view
   concern, derived, not persisted on the item). (D2)
3. **The UI DTO may carry paths; S9 guards the MCP surface only.** `AppPortView` and the native
   consumer view carry `contextDisplay` and the **logical** `scriptFolder` (real paths) — the
   authenticated admin UI is entitled to them; S9 (ARCH.md) forbids absolute paths on the **MCP**
   surface, not the web UI (028 precedent). `contextKey` (the physical/synthetic dedup key) stays
   **internal** — it is an identity key, never user-facing. `McpPathLeakArchTest` is unchanged; a new
   test asserts the UI DTO may carry a path while no `mcp/*Tool` does. (D3)
4. **Stacks on the integration branch** (`moacyrricardo/integration-057-061-062`), which has all of
   055–062, so the 059-followup can start immediately and ride the same stack to main. (D4)
5. **Seam-only.** 063 delivers the server/DTO + native-consumer channel and the minimal client
   plumbing to *carry* the fields; the actual **context-grouped discovery surfaces** (059 Surface 1 +
   Surface 2 grouping) are a dedicated **059-followup** spec built on top. 059 stays the shipped
   slice, unchanged. (D5)

## Implementation

### Widen the read DTOs (the parse→DTO hop)

- `MonitorService.AppPort` (`:78`) gains `contextDisplay`, `contextScripts` (`List<String>`),
  `sourceNote`, `confidence`, and the **logical** `scriptFolder`; `parseAppPortList`
  (`MonitorService.java:166–188`) reads them from the `app_port_list` JSON (they are already
  persisted by 055/056/061 — this is a read-side change only). Absent keys default to null/empty
  (old rows and docker-object rows parse unchanged — 061's tolerant-reader contract holds).
- `MonitorDtos.AppPortView` (`:392`) gains the same fields; `AppPortView.of(AppPort)` copies them.
- `RecipeDtos.RecipeView` (`:58`) gains an `appPortList : List<AppPortView>` (today it carries none),
  so the discovery/recipe read path can render per-context records. Populate it where `RecipeView`
  is assembled from the recipe entity's `app_port_list`.

### Derive the native-consumer channel (mirror the docker path)

- New `MonitorService.NativeConsumerData(String name, ConsumerRole role, ConsumerSource source,
  String contextKey, String contextDisplay, String confidence, List<String> appNames)`.
- New `parseNativeConsumers(...)` beside `parseDockerConsumers` (`:211`): group the parsed
  `AppPort`s by `contextKey`; per group emit one `NativeConsumerData` with
  `source = ConsumerSource.NATIVE`, `role = DATABASE` when the group's `confidence`/fingerprint marks
  a datastore (058's standalone pg/mysql/mariadb; the fingerprint already sets `confidence=high`),
  else `APP`; `name = contextDisplay` (logical). PROCESS/SYSTEMD-sourced items only — docker-cgroup
  items already route to the docker channel (056/061), never double-counted.
- Feed both channels into the **existing** `MonitorConsumerView` builder so docker and native
  consumers render through one path (059's tri-axis dashboard is already generic over
  `MonitorConsumerView` — no new UI type). The native consumer's axes reuse 057's footprint numbers
  where present; absent ⇒ honest null (not 0), same as docker.

### S9 / gate posture

- The two `mcp/*Tool` surfaces are **untouched**; no path reaches them. Paths land only on the
  **web** DTOs (`AppPortView`/`RecipeView`/native consumer), which no MCP tool reads. `GateArchTest`
  and `McpPathLeakArchTest` stay green with zero edits; a new arch/unit test pins the split (a UI DTO
  field may hold an absolute path; a scan of `mcp/*Tool` returns none).
- No migration (`app_port_list` already CLOB, V15; all fields already persisted). No new probe, no
  discovery change, no gate change, no MCP tool.

### Tests

Unit-test `parseAppPortList` round-tripping the new fields (incl. absent-key defaults and a docker
combined-object row); `AppPortView.of` copying them; `parseNativeConsumers` (grouping by
`contextKey`; `DATABASE` vs `APP` role from confidence; `source=NATIVE`; PROCESS/SYSTEMD-only, no
docker double-count; a 058 standalone-pg group ⇒ one DATABASE native consumer). Web-test the monitor
read carries context/confidence on `AppPortView` and one native consumer end to end. The S9 split
test above.

## Known Gaps

- **Seam-only by decision (D5).** The context-grouped discovery cards, source-note rendering,
  sibling-script lists, and Surface-2 context grouping are the **059-followup**, not here. This spec
  only guarantees the data *reaches* the client and native consumers *exist*.
- **`contextKey` stays internal.** The physical/synthetic dedup key is never exposed; the UI keys on
  `contextDisplay`. A future need to address a context by stable id would revisit this.
- **Native-consumer axes depend on 057 having run.** A discovered-but-never-monitored native context
  surfaces with null axes (identity only) until its footprint probe is approved+run — consistent with
  057's poll model, and the reason the footprint-placement question (mockup vs 059 Decision 1) is a
  separate UI concern, out of scope here.
- **Docker enrichment already covers its half** (061); 063 is the native counterpart. A host-network
  container seen by both channels relies on the existing spec-033 `appName` dedup, unchanged.
