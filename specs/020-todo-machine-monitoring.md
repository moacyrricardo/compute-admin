# 020 — Machine monitoring (concern)

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
  - Other app types (nginx status, a DB ping, …) are further siblings later.
- **Auto-discovered (reuses the spec-006 `RecipeDiscoverer` port).** Discovery proposes
  these like any other recipe (as `PENDING_APPROVAL`, never auto-run):
  - `monitor machine` — universal, proposed on **every** reachable box.
  - `springboot monitor` — proposed **pre-filled** with the `(app-name, port)` pairs a
    read-only probe detects: enumerate listening TCP ports owned by `java` processes
    (`ss -ltnp`, fallback `netstat -ltnp`), map each PID → cmdline (`/proc/<pid>/cmdline`
    or `ps`) to derive an **app-name** (the `-jar <name>.jar` / main class / a
    `-Dspring.application.name=` if present) and its **listening port**, and optionally
    confirm it's Spring Boot by GETting `/actuator/health` on that port. The operator
    reviews/edits the proposed pairs before approving.

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

Two **read-only** recipes — **`monitor machine`** (CPU, RAM+swap, disk) and an app-monitor
family starting with **`springboot monitor`** (per `(app-name, port)`, via actuator) —
**auto-discovered and approved once like any recipe**, surfaced on a recipe-driven UI with
a **client-side poll setting (default single; 5s/30s/1min/5min)**, **no server-side
time-series in v1**. **Q1 and Q2 are now decided** — Q1: accept per-poll `Run` rows + add
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
