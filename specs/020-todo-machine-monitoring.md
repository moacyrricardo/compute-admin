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
- **App-monitoring recipes (a family; first one: `springboot monitor`).** For apps
  running on the box. `springboot monitor` is parameterised by **(app-name, port) pairs**
  — a machine can run **several** Spring Boot apps, so the recipe takes a repeatable
  `(app-name, port)` so each app is labelled and probed. Each app's read uses Spring
  Boot's **actuator endpoints** — `/actuator/health` plus others (`/info`, `/metrics`,
  e.g. `jvm.memory.used`, `http.server.requests`) — to build the per-app monitor view.
  Other app types (nginx status, a database ping, …) become sibling app-monitor recipes
  later.
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

1. **Poll persistence / audit noise (the real question polling raises).** Each `run`
   today persists a `Run` row and is audited (who/what/when/exit). Polling `monitor
   machine` every 5s ≈ **12 Run rows/min per action** — DB growth + audit-trail noise.
   Options: (a) accept it and lean on **run retention** (extend spec-013's output eviction
   to also prune old `Run` rows); (b) add a lightweight **"read-now" path** for read-only
   monitor actions that executes + returns output **without persisting a `Run`** (telemetry
   reads then aren't audited — a deliberate trade). This — not approval — is the decision to
   settle before build.
2. **Repeatable composite `(app-name, port)` parameter (biggest modelling stretch).**
   The current `ParamDef` binds a single scalar per param; `springboot monitor` wants a
   *list of `(app-name, port)` pairs* in one action (label + probe each app). Options:
   extend the param model to a repeatable composite; model each app as its own
   action/instance; or run once per pair and aggregate in the UI. Touches
   `ParamDef`/`ParamBinder` and possibly the action shape.
3. **Spring Boot health source:** actuator `/actuator/health` (assumes actuator exposed,
   maybe secured) vs a plain port-open/process check. Configurable per action?
4. **View:** per-machine monitor panel vs a fleet overview; how the `monitor machine` and
   `springboot monitor` outputs are laid out (gauges? raw text? parsed values?).
5. **JVM/port detection method & privileges (for the `springboot monitor` proposal).**
   `ss -ltnp` shows the **PID/cmdline of a socket's owner only with sufficient privilege** —
   an unprivileged login user sees its *own* listeners' PIDs but not other users' (S5). So
   auto-detection may miss JVMs run by other users unless the probe can `sudo`. Decide:
   probe as the login user only (miss cross-user apps, note the gap), or allow an optional
   `sudo -n` read for detection. Also: which signal names the app — jar name vs
   `-Dspring.application.name` vs the actuator `/info` — and how confident must we be it's
   Spring Boot before proposing?

## Leaning (to confirm)

Two **read-only** recipes — **`monitor machine`** (CPU, RAM+swap, disk) and an app-monitor
family starting with **`springboot monitor`** (per `(app-name, port)`, via actuator) —
**auto-discovered and approved once like any recipe**, surfaced on a recipe-driven UI with
a **client-side poll setting (default single; 5s/30s/1min/5min)**, **no server-side
time-series in v1**. The one thing worth settling before build is **Q1 (persist every poll
vs a non-persisted read path)** — it shapes storage/audit. Everything else (composite param,
actuator-vs-port-check, layout, detection privileges) is implementation detail. **No
approval-invariant tension** — one approval unlocks polled runs.

## Sequencing

Independent of 018/019 (benefits from tags to filter which machines to monitor). Reuses
the spec-006 discovery port and the spec-013 run/eviction machinery. Graduates to a spec
once **Q1 (poll persistence)** and **Q2 (composite `(app-name, port)` param)** are decided.
