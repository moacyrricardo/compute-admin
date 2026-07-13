# 040 — Monitor as a runtime view over runs (and where "what is an app" should live)

## Problem

compute-admin's monitoring was conceived (spec-020) as a **runtime view assembled in
the UI over generic recipe / action / run**: *"the monitoring logic is just recipes;
the UI presents them."* Two drifts from that baseline now bother us.

**1. Monitor executions never surface *as runs*.** Every monitor poll already runs
through the ordinary gated path — `POST /runs`, a fan-out parent + per-item children —
and persists real `Run` rows. But the Runs screen is a **browser-local `localStorage`
log** (`ca.runs`, capped at 50), written only by the manual "Run action" flow
(`app.js` `Runs.remember`, the sole caller at ~`app.js:818`). There is **no server-side
run *list* endpoint** — `RunRS` exposes only `POST /runs`, `GET /runs/{id}`,
`/children`, `/cancel`, `/output`. So a monitor polling at 5 s produces a stream of
runs that never appear, while a manually-run `cron list` shows up immediately. This is
surfacing, not pruning (eviction would first produce a *visible flood*). The "runtime
view over runs" is, today, not actually a view over the runs.

**2. A classification vocabulary accreted server-side.** Monitoring added **no new
table or entity**. The entire backend "model" is the pre-existing `recipe.type` column
carrying one new value (`RecipeType.MONITOR`) plus the reused `app_port_list` JSON
column. But a *taxonomy* grew at the discovery / DTO seam: the
`ConsumerRole` / `ConsumerSource` / `Dedication` / `Bucket` enums (spec-032) and the
discovery-time compose grouping + `DatastoreImages` classification (spec-033/038).
Every live metric is `null` server-side and computed in the browser — so **"what is an
app" is derived twice**: classified server-side at discovery, then re-joined and
normalized client-side at poll. That double-derivation is precisely the seam the
spec-038 grouping bug lived in.

**Honest nuance (what is genuinely forced).** Discovery must run over SSH and land
**gated** proposals, and docker's own labels are the only reliable way to see
bridge-networked / portless / datastore containers the host `ss → PID → /proc` chain
structurally misses — so *some* server footprint is unavoidable. Crucially, **S9 is
*not* a forcing reason here**: `GET /api/monitor` is a UI-only `@Secured` endpoint that
already returns `host`/`port`/`loginUser`, and no MCP tool reads the monitor surface at
all. The question is therefore **where the line sits**, not whether there is a bug.

## Leaning (recorded — this stays a concern, options open)

The stated preference is a **really thin backend**:

- **Discovery (BE) surfaces commands + params.** That is its job — propose gated
  actions; nothing more.
- **Heavy lifting lives in the UI** — grouping, classification, host-% normalization,
  "what is an app."
- The BE *may* do **transient, per-request, BFF-like shaping** to make the UI's job
  easier — but **not a persisted classification data model**.

This is Option C below, sharpened with the BFF nuance. The other options are kept for
the record and to frame the trade-offs.

## Hypotheses / Options

- **A — Accept & document.** Declare the current split load-bearing (discovery must be
  server-side + gated) and simply record why. Cheapest; leaves the double-derivation
  and the invisible runs in place.

- **B — Surface runs as the runtime view** *(orthogonal — compatible with any other
  option)*. Add an owner-scoped `GET /runs` list + a run lens that distinguishes
  monitor from user-initiated (the `Run` entity already persists `createdAt`, `action`,
  `machine`, `callerUserId`/`via`, `parentRunId` — enough to list, scope, and collapse
  a fan-out into one row). Add a retention policy for the poll firehose. Makes
  "monitor = runs" literal.

- **C — Thin toward UI assembly** *(the leaning)*. Discovery persists only **commands +
  params** (gated proposed actions). Re-expose to the browser the raw probe output it
  needs — the `docker ps` + label JSON — which it already complements with
  `docker stats` / `ps -s` / `system df -v` while polling. Grouping / classification
  (`DatastoreImages`, compose grouping, dedicated-vs-shared, buckets) moves into
  `app.js`, or into a **transient BFF endpoint** that shapes per request *without
  persisting a model*. The `ConsumerRole`/`Source`/`Dedication`/`Bucket` enums stop
  being persisted vocabulary. Kills the double-derivation; the backend carries only
  identity + gated proposals.

- **D — Commit the other way: a real backend read-model.** A server sampler /
  projection (even a `MonitorReading` table), UI goes thin. Coherent, but the *opposite*
  of spec-020, heavier, and would re-engage S9 (a projection consumed beyond the UI).
  *Rejected by the leaning; kept for contrast.*

- **E — Split the questions.** Treat "surface runs" (B) and "where grouping lives" (C)
  as independent decisions and sequence them — likely B first (it is orthogonal and
  low-risk), then the C refactor.

## Open Questions

1. **Where exactly is the BFF line?** Transient per-request server shaping is allowed;
   a *persisted* classification model is not. Is `MonitorService`'s read-time host/app
   partition + rollup on the allowed side of that line, or should even that move to the
   client?
2. **What does discovery persist under C?** Only proposed actions (commands + params)
   for the gate + spec-021 idempotency? What becomes of the docker-consumer JSON
   currently frozen onto `app_port_list` — dropped, or recomputed transiently from raw
   output on read?
3. **Re-exposing raw `docker ps` / label output to the UI — any sensitivity?** The
   monitor read already exposes `host`/`port`/`loginUser` and is UI-only, so probably
   none; confirm labels don't leak anything the app-name `ALLOWED_SET` hashing was
   protecting.
4. **Is `RecipeType.MONITOR` still needed?** It is load-bearing only as the dashboard
   enumeration filter (`MonitorService`, `type == MONITOR`); the gate ignores it. Under
   C, could the UI identify monitor recipes by action shape (host reads vs
   `APP_PORT_LIST` probes) instead — or is the coarse type worth keeping as a cheap
   marker on an already-existing column?
5. **Retention if runs are surfaced (B).** A visible monitor log inherits the
   24 h / 500-per-action eviction windows (`RunRowEvictionJob`), or needs its own
   policy. What retention makes the runtime view useful without bloating the DB — or do
   monitor runs stay ephemeral and the "view" reads live poll state, not history?
6. **Does adopting C partly unwind 032 / 033 / 038?** Their server-side classification
   would move client-side. Note here what becomes invalid (per the catalog convention:
   a resolving spec adds a WARNING to what it supersedes).

## Related

- Baseline & prior decisions: spec-020 (the umbrella framing), spec-022 (deliberately
  *declined* a first-class `App` entity — the label convention instead), spec-032 (the
  consumer contract), spec-033 / spec-038 (docker grouping & one-card-per-project).
- spec-031 (deferred-follow-ups triage) may naturally absorb the run-list endpoint.
- ARCH.md: S2 (only the server holds the SSH key → discovery is server-side),
  S4 (no looping/variable commands → fan-out stays server-side), S9 (MCP host hiding —
  *not* engaged by the UI-only monitor read).
