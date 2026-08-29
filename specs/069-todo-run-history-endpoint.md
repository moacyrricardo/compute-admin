# 069 — Server-backed run history

**Status:** todo · Linear [BOL-898](https://linear.app/iskeru/issue/BOL-898) · build branch `moacyrricardo/bol-898-cpt-069-run-history-endpoint` · **Part of concern 064 (mockup delivery).** _Optional child._

**OPTIONAL child.** 067's machine dashboard ships without this spec — it degrades to the
browser-local recent-list the UI already keeps. 069 only upgrades that list to durable,
cross-device history served by the API.

## Context

The run engine (spec-005) has **no list endpoint**. `RunRS` (`run/api/RunRS.java`) exposes
exactly: `POST /runs` (`:47`), `GET /runs/{id}` (`:62`), `POST /runs/{id}/cancel` (`:73`),
`GET /runs/{id}/children` (`:85`), and the SSE stream `GET /runs/{id}/output` (`:92`). Every
read is owner-scoped in the service — `RunService.requireRun` resolves through
`findByIdAndAction_Recipe_Machine_Owner_Id` (`run/service/RunService.java:408-409`,
`run/repository/RunRepository.java:24`), so a not-owned or absent id is a 404 and existence
never leaks (ARCH.md:22-26).

The UI papers over the gap with a **browser-scoped cache**: `Runs` in
`static/app.js:92-98` keeps the last 50 launches in `localStorage["ca.runs"]` — the comment
at `app.js:87` says why ("The run engine (spec-005) exposes no 'list runs' endpoint").
Entries are written only at launch time (`app.js:1070-1074`, capturing `actionName`, `host`,
the resolved `command`, and `params` — none of which `RunDtos.RunView` carries,
`run/api/RunDtos.java:31-32`). The Runs index renders that cache and admits it
(`app.js:1225-1240`: "shows runs launched from this browser plus a by-id lookup"; empty
state "No runs launched from this browser yet").

The 054 mockup's machine dashboard has a **Recent runs** section
(`specs/054-assets/mockup-compute-admin.html:883-905`): per-machine rows of
`app · action`, relative time, duration, exit code, and a status chip (DONE / RUNNING /
FAILED / INTERRUPTED). Spec 067 composes that dashboard; with only `ca.runs` it shows runs
launched *from this browser*, filtered client-side by `machineId` — MCP-initiated runs
(`Via`, `run/model/Run.java:69-72`) and runs launched from another device never appear.

## Decision

Add **`GET /runs?machineId=<id>`** — a paginated, owner-scoped, newest-first list of a
machine's **top-level** runs, returning a summary DTO fit for the dashboard's Recent-runs
rows. Read-only; no gate enforcement point changes and no S9 exposure change (the endpoint
is UI-surface, carries no absolute paths, and adds no MCP tool).

1. **Query shape.** `machineId` required (400 if absent — matching `RunRS.run`'s
   validation style, `RunRS.java:51-53`); `limit` (default 20, cap 100) and `before`
   (`createdAt` cursor, exclusive) optional. Newest-first by `createdAt`, matching the
   pruning queries' ordering convention (`RunRepository.java:61-64`).
2. **Top-level rows only** (`parentRunId is null`, `Run.java:95-96`) — fan-out children
   (spec-022) are an implementation detail of one poll; the dashboard list shows the unit
   the user launched, exactly as the eviction machinery treats it
   (`RunRepository.java:42-46`).
3. **Ownership: scope by `machine.owner`.** `Run` holds a direct required `machine` FK
   (`Run.java:60-63`), and ownership derives through the machine (`Run.java:38-39`,
   ARCH.md:25-26); an unowned/absent `machineId` returns 404 via the machine registry's
   existing require-owned lookup, never an empty 200 — existence never leaked.
4. **Summary DTO, not `RunView`.** A new `RunDtos.RunSummary` adds what the mockup rows
   need and `RunView` lacks: `actionName`, `recipeName` (the "payments-api · restart"
   line, mockup `:886`), plus `id, status, exitCode, via, createdAt, startedAt,
   finishedAt`. Still no stdout/stderr — the DTO-level secrets rule stands
   (`RunDtos.java:14-17`).
5. **Explicitly optional.** 067 renders Recent runs from `ca.runs` when this endpoint is
   absent; when present, the same section reads the server list and the local cache
   remains only the source for the resolved `command`/`params` panels on the run detail
   view (`app.js:1121`), which the server still doesn't serve per-field.

## Implementation

- **Repository:** derived pageable query on `RunRepository`, e.g.
  `findByMachine_IdAndMachine_Owner_IdAndParentRunIdIsNullOrderByCreatedAtDesc(String machineId,
  String ownerId, Pageable page)` (plus the `before` variant with a `createdAt <` bound) —
  same navigation-path scoping idiom as `RunRepository.java:24`.
- **Service:** `RunService.listRuns(machineId, limit, before)` — require the machine
  through the owner-scoped machine lookup first (404 semantics per
  `RunService.java:129-141`), then page. Fetch of `action.name` / `action.recipe.name` for
  the summary happens inside the service (join fetch or DTO projection) so the LAZY
  associations (`Run.java:55-63`) never leak into the RS layer.
- **RS:** a `@GET` (no `@Path`) method on the existing `RunRS` class — the resource root
  `GET /runs` slot is currently unclaimed. `@Secured` is class-level already
  (`RunRS.java:37`).
- **DTO:** `RunDtos.RunSummary` record with static `of(Run)` — the house no-mapper rule
  (`RunDtos.java:10-13`).
- **UI:** the 067 Recent-runs section calls `GET /runs?machineId=` and falls back to
  `Runs.all()` filtered by `machineId` (`app.js:92-101`) on 404/error; the Runs index
  (`app.js:1227`) can gain a per-machine server view later but is not in 069's scope.
- **Tests:** owner-isolation (user B's list of user A's machine is 404), child exclusion
  (a fan-out parent appears once, children never), ordering + `before` pagination, 400 on
  missing `machineId`.

## Known Gaps

- **This whole spec is OPTIONAL for the mockup epic.** 067's dashboard works today from the
  `ca.runs` localStorage cache (`app.js:92-98`) — browser-scoped, launch-time-only, capped
  at 50 — and that may suffice for v1. 069 adds durability, cross-device visibility, and
  MCP-launched runs (`Via.MCP` rows never enter `ca.runs`); build it only when those bite.
- **Server history is itself bounded.** `RunRowEvictionJob` prunes terminal top-level runs
  past `ca.run.row-retention` (default **24h**) and beyond `ca.run.rows-per-action-max`
  (default 500) (`run/service/RunRowEvictionJob.java:53-60`). "Durable" means
  cross-device within the retention window, not forever; raising retention is a config
  decision, not part of this spec.
- **The resolved command/params stay local-only.** `RunSummary` does not expose
  `paramsJson`/`resolvedArgvJson` (`Run.java:74-80`) — the run detail's "command that ran"
  panel keeps depending on the launch-time cache (`app.js:1121`). Exposing those
  server-side is a separate decision (secrets-in-params, `RunDtos.java:14-17`).
- **No cross-machine "all my runs" list.** `machineId` is required; a fleet-wide runs
  index is out of scope until a consumer exists.
- Design forks between the mockup and settled decisions (footprint badge placement, verb
  badges, declared apps, fonts, mobile nav) are recorded as Open Questions in **concern
  064** — this child takes no position on them.
