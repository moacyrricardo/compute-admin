# 020 — Machine monitoring (concern)

> **GRADUATED (2026-07).** This concern has been resolved into six build-ready
> specs — it stays the **umbrella concern of record** (the problem framing and the
> Q1/Q2 decisions below remain the rationale), but the *how* now lives in:
> - **[021](./021-todo-discovery-idempotency.md)** — discovery idempotency
>   (reconcile, don't duplicate; resolves backlog **H2**) — **prerequisite, build first**.
> - **[022](./022-todo-monitoring-foundations.md)** — foundations: `RecipeType.MONITOR`
>   classification, the `appName`/`runtime` label convention (+ double-detection
>   link), the `APP_PORT_LIST` param + **fan-out run mode** (the S4-safe piece), and
>   run-row pruning (extends spec-013 eviction). *Pins Q1 + Q2.*
> - **[023](./023-todo-monitor-machine-recipe.md)** — the universal `monitor machine`
>   host-vitals recipe (cpu/ram+swap/disk).
> - **[024](./024-todo-monitor-ui-dashboard.md)** — the dashboard: host panel +
>   per-app cards, client-side polling (single·5s·30s·1m·5m), per-app detail drawer.
> - **[025](./025-todo-app-monitor-recipes.md)** — the app-monitor family
>   (springboot·fastapi·generic), discovery-routed by classification.
> - **[026](./026-todo-app-ops-recipes.md)** — app-ops as a label **facade** over
>   existing runtime recipes (restart/logs/redeploy); + optional `SystemdDiscoverer`;
>   follow-mode logs flagged as blocked on a new **run-cancellation** engine addition.
>
> **Build order:** 021 → 022 → (023 · 024) → 025 → 026. The decisions in those specs
> are made — do not re-open them from this concern.

> **Concern** (exploratory — options open, not a decided spec). Linear is BLOCKED;
> commits use `spec-020`. The shape is concrete (see Design); what's open is **modelling
> detail** (Open Questions), not a security decision. *(An earlier draft of this concern
> wrongly framed a "per-run approval / read-only carve-out" problem — corrected in the
> Approval-model note below: there is no per-run approval, so polling is a non-issue for
> the gate.)*

## Problem

We want to **monitor** machine health — CPU, RAM/swap, disk — and also **per-app**
health (a box often runs several Spring Boot apps on different ports), inside
compute-admin's model: an SSH-recipe engine with **UI-only approval**, no time-series
storage, and no general scheduler. Readings stay fresh by putting the refresh loop in
the **browser**, not the server.

## Design (the intended shape)

- **The monitor is a UI driven by a monitor recipe.** The dashboard renders the output
  of monitor **recipe actions** (the existing run/stream machinery), per machine — the
  monitoring "logic" is just recipes; the UI presents them.
- **Client-side polling setting.** The dashboard defaults to a **single request** (run
  once, show the result). The user can switch the refresh cadence to **every 5s / 30s /
  1min / 5min**, and the *browser* re-runs the actions on that interval. No server-side
  sampler, no stored time-series in v1: the dashboard shows the *current* reading,
  refreshed at the chosen cadence. (Historical charts / retention would be a separate,
  later concern.)
- **Built-in recipe: `monitor machine`.** Read-only actions for the core vitals:
  - **CPU usage** — `top -bn1` / `/proc/stat` / `uptime` load,
  - **RAM + swap usage** — `free -m` (or parse `/proc/meminfo`; both mem and swap),
  - **disk usage** — `df -h`.
- **App-monitoring recipes (a family).** Sibling recipes per app framework, all sharing
  the fan-out `(app-name, port)` model (Q2) and the monitor UI, differing in **how they
  probe**. Two strategies compose:
  - **Endpoint probe** — HTTP GET a health/metrics path per app. Reliable where a framework
    has a standard observability contract; the mainstay of **`springboot monitor`**
    (actuator `/actuator/health`, `/beans`, `/metrics` — e.g. `jvm.memory.used`,
    `http.server.requests`).
  - **Process probe** — resolve the app's listening port → PID (`ss -ltnp`) → read OS-level
    stats from `/proc/<pid>/` (RSS memory `VmRSS`, CPU from `stat`, thread/fd counts,
    uptime). **Framework-agnostic** — works even when the app exposes nothing; the baseline
    fallback (and useful for Spring Boot too).
  - **`springboot monitor`** — endpoint-probe first (actuator), process-probe as a
    supplement.
  - **`fastapi monitor` (planned sibling).** FastAPI has **no Actuator equivalent** — no
    built-in health or metrics endpoints. Its only guaranteed built-in is **`/openapi.json`**
    (+ `/docs`): a good *liveness / "is this FastAPI"* signal, not health/metrics. Health is
    an ad-hoc developer route; metrics exist only if the app added Prometheus
    (`/metrics`). So `fastapi monitor` leans on the **process probe** for real resource
    usage, plus **optional, configurable endpoint probes**: a health-path param
    (default `/openapi.json`) and an optional `/metrics` scrape when present. *Note:*
    gunicorn/uvicorn run several worker PIDs per app — the process probe aggregates across
    workers.
  - **`generic app monitor` (fallback).** For any listening app discovery **cannot**
    classify as a known framework: **process probe only** (`port→PID→/proc`:
    RSS/CPU/threads/uptime), no endpoint assumptions. Guarantees every detected app still
    gets real resource metrics even when we don't know what it is.
  - Other framework-specific siblings (nginx status, a DB ping, …) come later; anything
    unrecognised falls back to `generic app monitor`.
- **How the UI assembles the dashboard (monitor-action classification).** Monitor actions
  are **marked as monitors** so the UI can enumerate them without hard-coding a recipe list:
  the dashboard lists **all actions classified `monitor`** across the user's recipes and
  groups them — **host-level** monitors (`monitor machine`: CPU/RAM/disk, no app param) into
  a host panel, and **app-level** monitors (those carrying the `(app-name, port)` param) into
  a **per-app card**, correlating each app's several endpoint actions (health, beans, …)
  under its name. This convention — *monitor classification + the `(app-name, port)` param* —
  is exactly what lets the specific (`springboot`, `fastapi`) and `generic` recipes coexist:
  the UI keys off the classification, not off which recipe produced the action. *(Pin at
  build: classification via a `RecipeType.MONITOR` vs a per-action monitor flag.)*
- **Auto-discovered & routed (reuses the spec-006 `RecipeDiscoverer` port).** Discovery
  proposes monitors like any recipe (`PENDING_APPROVAL`, never auto-run):
  - `monitor machine` — universal, proposed on **every** reachable box.
  - **App monitors, routed by classification.** A read-only probe enumerates listening TCP
    ports owned by app processes (`ss -ltnp`, fallback `netstat`), maps each PID → cmdline
    (`/proc/<pid>/cmdline` / `ps`) to an **app-name** + **port**, then **classifies** the app
    and proposes the matching recipe pre-filled with the `(app-name, port)`:
    - Spring Boot (java + `-jar`/main class, or `/actuator/health` responds) → **`springboot monitor`**;
    - FastAPI (uvicorn/gunicorn cmdline, or `/openapi.json` responds) → **`fastapi monitor`**;
    - otherwise → **`generic app monitor`** (process-probe only).
    The operator reviews/edits the proposed apps before approving.

## Approval model (clarification — there is no per-run approval)

compute-admin approves an **action once** (UI-only): once an action's command template +
param schema is `APPROVED`, it runs **whenever** — from the UI *or* MCP — with params
validated against the approved schema; the content-hash only blocks *drift* (an edited
action drops back to `DRAFT`). **There is no per-run approval.** That
approve-once-then-run-freely model is exactly what lets an MCP agent run approved actions
autonomously — it's core to how MCP works.

So a **polling dashboard is not a gate problem**: it just **re-runs an already-approved
monitor action** every tick. Monitor recipes are proposed by discovery as
`PENDING_APPROVAL` and approved **once** like any recipe. **No "read-only carve-out" is
needed or wanted** — auto-approving anything would weaken the UI-only-approval invariant
for zero benefit, since one approval already unlocks unlimited (including polled) runs.

## Open Questions

1. **Poll persistence / audit noise — DECIDED for v1 (accept, with forward work).**
   Each `run` persists an audited `Run` row; polling every 5s ≈ **12 rows/min per action**.
   *v1 accepts this* (option a) — it's tolerable to get moving — **but it is not the end
   state**: unbounded `Run` rows must not accumulate, so v1 at minimum extends spec-013's
   eviction to **prune old `Run` rows** (retention). The better long-term answer is a
   lightweight **non-persisted "read-now" path** for read-only polled reads (option b) —
   flagged as **forward work** to design next, not built in v1.
2. **How several apps are represented — DECIDED (repeatable composite param, executed by
   fan-out).** The `springboot monitor` recipe has **one action per actuator endpoint** —
   e.g. `health`, `beans` (later `metrics`/`info`). Each action is parameterised by a
   **repeatable `(app-name, port)` list** (the apps on the box), supplied as a **runtime
   value** validated against the action's schema (each item: `app-name` pattern + `port`
   INT_RANGE). The **monitor UI orchestrates** the per-endpoint outputs into meaningful
   per-app data — an `orders` card showing its health + beans, a `billing` card, etc.
   - **Critical implementation rule (keeps the spec-004 gate / S4 injection guarantee
     intact):** an action with a repeatable param **fans out at the execution layer** — the
     engine runs the **fixed single-app template once per `(app-name, port)` item**
     (`curl -s localhost:<port>/actuator/<endpoint>`) and **aggregates the labelled
     outputs**. It does **not** build a looping/variable shell command. Every invocation is
     still the fixed template with discrete, validated tokens, so injection-safety holds
     exactly as today.
   - The app list is a **runtime value**, not part of the content-hash (which covers
     template + schema), so **changing which apps are probed needs no re-approval**;
     discovery pre-fills the list and the UI can edit it. Approval stays **once per action**.
   - **Model changes required (bounded; do not touch the injection core):** a
     **repeatable-composite `ParamKind`** (`(name, port)` items) and a **fan-out run mode**
     (run the fixed template per item, aggregate labelled output) in `ParamBinder`/`RunService`.
3. **Spring Boot health source:** actuator `/actuator/health` (assumes actuator exposed,
   maybe secured) vs a plain port-open/process check. Configurable per action?
4. **View:** per-machine monitor panel vs a fleet overview; how the `monitor machine` and
   `springboot monitor` outputs are laid out (gauges? raw text? parsed values?).
5. **App/port detection & process-probe privileges.** `ss -ltnp` shows the **PID/cmdline of
   a socket's owner only with sufficient privilege** — an unprivileged login user sees its
   *own* listeners' PIDs but not other users' (S5) — and the **process probe** reading
   `/proc/<pid>/` of another user's process hits the **same limit**. So both auto-detection
   and process-metric reads may miss apps run by other users unless the probe can `sudo`.
   Decide: probe as the login user only (miss cross-user apps, note the gap) vs an optional
   `sudo -n` read. Also: which signal names a Spring Boot app (jar name vs
   `-Dspring.application.name` vs actuator `/info`) and how confident before proposing?

## Leaning (to confirm)

A host recipe **`monitor machine`** (CPU, RAM+swap, disk) plus an **app-monitor family** —
specific **`springboot monitor`** (actuator) and **`fastapi monitor`** (process + optional
`/openapi.json`·`/metrics`), with a **`generic app monitor`** (process-probe only) fallback
for apps discovery can't classify — all **discovery-routed, per `(app-name, port)`, and
approved once like any recipe**. Surfaced on a UI that **enumerates monitor-classified
actions** into a host panel + per-app cards, with a **client-side poll setting (default
single; 5s/30s/1min/5min)**, **no server-side time-series in v1**. **Q1 and Q2 are now decided** — Q1: accept per-poll `Run` rows + add
run-row pruning (non-persisted "read-now" path is forward work); Q2: one action per actuator
endpoint, each taking a repeatable `(app-name, port)` list executed by **fan-out** (fixed
template per item), the UI pivoting into per-app cards. Everything else (endpoint set,
actuator-vs-port-check, layout, detection privileges) is implementation detail. **No
approval-invariant tension** — one approval unlocks polled runs. **This concern is ready to
graduate into a full spec.**

## Sequencing

Independent of 018/019 (benefits from tags to filter which machines to monitor). Reuses
the spec-006 discovery port and the spec-013 run/eviction machinery. **Q1 and Q2 are
decided** — ready to graduate from concern into a full spec (Context / Decision /
Implementation / Known Gaps) on request, at which point it takes a `doing` status and a
build branch.
