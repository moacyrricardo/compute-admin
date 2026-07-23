# 051 — Run execution policy: long-running / wait-on-shutdown opt-in

## Problem

Some runs are **long-running by nature**: a `deploy.sh` that builds, pulls, migrates
and restarts (spec-050's lifecycle verbs; spec-026(d)'s redeploy `CUSTOM` actions), a
database dump proposed by `DatabaseDiscoverer`, a `tail -f` follow stream. Today the
run engine treats every run identically at shutdown: spec-016's graceful drain gives
in-flight runs a **bounded window** — `server.shutdown: graceful` +
`spring.lifecycle.timeout-per-shutdown-phase: 25s` in `application.yml`, with the
`runExecutor` pool awaiting up to `ca.run.shutdown-await-seconds` (default 20,
`AsyncConfig`) — and then gives up.

What "gives up" means is physical, not bookkeeping. A run executes over an **SSH exec
channel the controller itself owns** (`MinaSshExecutor` on the shared Apache MINA
`SshClient`; the `SshExecutor` port). When the JVM exits past the drain window, the
daemon worker threads are abandoned and the channel tears down — the remote side sees
its session close and the script gets SIGHUP, dying **mid-run**. For a monitor probe
that is a non-event. For a deploy it can mean a half-applied migration. On next boot
spec-016's `RunReconciler` marks the orphaned `RUNNING`/`QUEUED` row
`INTERRUPTED` (`exitCode = -1`, sentinel note: *"the remote command's actual outcome
is unknown"*) — honest, but after the damage.

Spec-050 hit this head-on (its "Long deploy vs graceful shutdown" residual, deferred
here): a multi-minute lifecycle `deploy` cannot finish inside 25 s, its v1 answer is
an operational rule ("don't redeploy the controller while a deploy is in flight") plus
the reconciler's note. The idea this concern explores: let a run **opt into how it is
treated on shutdown** — default stays the bounded drain; opt-in means an **extended
drain** ("wait for me before dying"). Surfaced as a checkbox on the run form in the
UI, and declared so the MCP `run` path (`RunActionTool` → `RunService.run`) carries
the same thing uniformly, with no MCP special-casing.

## The distinction that matters: an execution option, not a param

This flag is **not a command param**. A spec-007 param (or the reserved `app-name`
correlation param, spec-026) is data bound *into the argv* by `ParamBinder.bind` —
validated, quoted, and ultimately seen by the remote script. The wait-on-shutdown
flag is the opposite: a **run-level execution option — a directive to compute-admin's
own run engine** about how to schedule/drain this run. It never reaches the target,
never appears in the argv, is not part of the approved content hash
(`ActionSnapshot.hash`), and widens no S4 surface because it is never a token the
adapter quotes.

Getting this modeling right is what makes the feature cheap everywhere at once:

- **UI**: one checkbox beside the params on the run form — it rides the
  `POST /api/runs` request body (`RunDtos.RunRequest` is `{machineId, actionId,
  params}` today), not the params map.
- **MCP**: one optional input field on the `run_action` tool schema, passed through
  to the same `RunService.run` entry point — the single-gate property (spec-005/008)
  is untouched.
- **Gate**: nothing to re-approve. The approval binds *what runs* (argv template,
  params, script bytes); this option changes only *how long the controller is willing
  to keep its own process alive for it*. Conversely, if the option were smuggled in
  as a param, it would pollute the content hash, demand fake validation kinds, and
  leak an engine directive into every remote script's argv.

Where the *declaration* lives (per-run field only, action-level default, recipe
attribute) is Option C below — genuinely open.

## The ceiling: the OS kill timeout (no hand-waving)

The app can **never** wait longer than the platform lets it. Spring's drain runs
between SIGTERM and process exit — but the supervisor enforces its own deadline:
under systemd the unit's `TimeoutStopSec` (the deploy unit used for compute-admin
sets it around **35 s**; no unit file lives in this repo), under Kubernetes
`terminationGracePeriodSeconds` (default **30 s**). Past that deadline the OS sends
**SIGKILL** regardless of what Spring's shutdown phase is still awaiting. And a
SIGKILL mid-deploy is strictly *worse* than today's graceful path: no reconciler-run
JVM shutdown bookkeeping, potentially a corrupted H2 write in flight, and the same
dead SSH channel anyway.

So "wait until finishes" is a fiction. The honest maximum this feature can offer is
an **extended, still-bounded drain whose ceiling the operator must raise in the
platform config to match** — `TimeoutStopSec` / `terminationGracePeriodSeconds` must
exceed the app-side extended window, or the opt-in converts graceful SIGHUPs into
SIGKILLs. This coupling between an app config key and an out-of-repo platform knob is
the core tension of the whole concern, and "how we handle the ceiling" is treated as
an open question, not a footnote. Candidate stances:

- extended bounded drain + **deployment-doc guidance** ("if you raise
  `ca.run.extended-drain-seconds` above ~10 s of headroom under `TimeoutStopSec`, you
  are trading graceful for SIGKILL");
- **refuse to start** a new opt-in long run once shutdown is known to be in progress
  (the graceful phase already stops accepting HTTP, so this is mostly free — but a
  run *queued before* SIGTERM is the real case);
- a **hard app-side cap** on the extended window (e.g. never above a configured
  fraction of a declared platform timeout), making misconfiguration loud at boot
  instead of silent at kill time.

**Explicitly off the table: detaching the remote process** (`nohup`/`setsid`/`&`
wrappers so the script survives the controller). That is precisely the
daemonization-by-inference spec-050 forbids — fabricating a backgrounding wrapper
over SSH changes the script's own contract, breaks the "the channel's exit drives the
terminal state" model, orphans the run's output stream, and re-opens the S4 operator
ban. If a script wants to survive its channel, that is the *script's* property
(spec-050 `selfBackgrounds`), never something the engine injects.

## Leaning (recorded — this stays a concern, options open)

**A is the safe baseline and stays the default**: the bounded 25 s drain +
reconciler, with spec-050 v1 consuming it as-is (warning + operational rule). It is
already built, already honest, and already backstopped. **B — the per-run extended
drain opt-in — is worth building only as a package deal with the platform-timeout
guidance**: an extended app-side window without the matching `TimeoutStopSec` /
`terminationGracePeriodSeconds` story is a footgun *upgrade* (SIGHUP → SIGKILL), not
a fix. If B lands, the leaning within C is the **run-request field with an optional
action-level default** (discoverable where the risk lives — the action — while
keeping the run request the single carrier both UI and MCP already share). But none
of this is decided; the ceiling question and the eligibility question below could
still tip the answer back to "A forever, document harder."

## Hypotheses / Options

- **A — Status quo + guard rails** (what spec-050 v1 adopts). Bounded 25 s drain for
  everyone; an interrupted long run lands `INTERRUPTED` with the reconciler's
  sentinel; spec-050 adds the run-time warning and the "don't redeploy the controller
  mid-deploy" rule; optionally an in-flight marker on the UI ("a deploy is running —
  restarting compute-admin now will sever it"). **+** zero new machinery, no platform
  coupling, the footgun is signposted. **−** the footgun exists: a routine controller
  redeploy silently guillotines a tenant's migration; the operator must *remember*
  the rule, and MCP-driven agents won't.

- **B — Per-run "extended drain" opt-in** (the main body of this concern). A declared
  run option; at shutdown, the drain logic waits on flagged runs up to a longer —
  still bounded — window (`ca.run.extended-drain-seconds`?), while unflagged runs
  keep the 20 s await. Mechanically this likely means the drain can no longer be just
  `ThreadPoolTaskExecutor.setAwaitTerminationSeconds` (one pool-wide number): either
  the await becomes max-of-flagged-windows, or flagged runs execute on a second pool
  with its own await, or a custom `SmartLifecycle` phase polls the in-flight set —
  each with different blast radii on `AsyncConfig` and the spec-016 `@DependsOn`
  drain-before-SSH-client ordering. `spring.lifecycle.timeout-per-shutdown-phase`
  must also rise (it caps every phase), which lengthens *worst-case* shutdown even
  when nothing is flagged — unless the phase timeout is itself derived. **+** the
  deploy-mid-shutdown case gets real protection; the flag is auditable on the `Run`
  row; MCP agents get the same lever humans do. **−** platform-timeout coupling (the
  ceiling above); slower controller restarts when flagged runs are in flight;
  more shutdown machinery in exactly the code spec-016 fought to keep simple.

  **Candidate default (proposed): a 90 s deploy window.** Rather than a free-form
  per-run number, the concrete proposal is that `deploy`/`update` runs default to a
  **90 s** drain window (vs the current ~25 s baseline). 90 s covers a typical
  build/pull/migrate/restart cycle without being open-ended. **The coupling is
  mandatory, not optional:** 90 s of app-side drain is inert — worse, a SIGHUP→SIGKILL
  upgrade — unless the platform stop-timeout is raised to match. Today's drafted
  systemd unit uses `TimeoutStopSec≈35 s`, so adopting 90 s means bumping it to
  `≥ ~100 s` (a little headroom over 90) and, on k8s, `terminationGracePeriodSeconds`
  likewise; and `spring.lifecycle.timeout-per-shutdown-phase` must clear 90 s too. So
  "90 s deploy window" is really a **three-place change** (app default + phase timeout
  + platform stop-timeout), and the concern flags whether the app should refuse to
  honour a 90 s window it can detect it will not get (Open Question 1). Whether 90 s is
  a per-verb default (only `deploy`/`update`) or a per-run opt-in that deploy
  pre-checks is itself the C-question below.

- **C — Where the option is declared.** Sub-options, not mutually exclusive:
  - **C1 — run-request-only.** A field beside `params` on `POST /runs` and the
    `run_action` MCP schema; persisted on the `Run` row for the drain/audit. **+**
    smallest surface, no action/recipe schema change, no approval interaction at all.
    **−** discoverability: the caller must know to ask, every deploy run must
    remember the checkbox; agents will forget.
  - **C2 — action-level default (run can override).** The action that *is* a deploy
    declares "runs of me default to extended drain"; the run form pre-checks the box.
    Since an action edit resets approval (spec-004), the human approving a deploy
    would also see its drain posture — arguably right, since "this holds the
    controller open for minutes" is approval-relevant context, even though it is not
    part of what executes. **+** the default lives where the knowledge lives
    (whoever authored/approved `deploy.sh` knows it is long). **−** touches the
    action model and possibly `ActionSnapshot` questions (in or out of the hash?);
    discovery (spec-050's `LifecycleDiscoverer`) would have to guess the default for
    proposed `deploy` verbs.
  - **C3 — recipe-level attribute.** Coarsest: "everything in `lifecycle <app>` is
    long". **+** one flag covers the family. **−** wrong granularity (a `status`
    action in the same recipe is instant); recipes are otherwise nearly attribute-free
    containers, and spec-040's thin-model leaning frowns on new persisted
    classification vocabulary.

- **D — Eligibility & the MCP question.** Who may opt in? Any run (simplest — the
  flag only ever *extends a bounded wait*, it grants no new capability)? Only
  mutating/`sudo` actions (the ones where severing hurts)? Only `CUSTOM`/lifecycle
  verbs? On MCP: carrying the flag is **not an approval-gate concern** — the MCP
  caller can already only run APPROVED actions through the same `RunService.run`
  gate, and this option approves nothing (no `mcp/` reference to `ApprovalService`;
  `GateArchTest` untouched). What it *can* do is hold the controller's shutdown open
  longer — a mild availability lever (ARCH.md S7 territory, not the gate). An agent
  flagging every run would make every controller restart wait the full extended
  window. Mitigations to weigh: cap the number of concurrently-flagged runs, ignore
  the flag on non-mutating actions, or let the shutdown path log loudly which runs it
  is waiting on. Note the MCP `run_action` tool already *returns* after
  `MAX_WAIT_SECONDS = 120` of streaming while the run continues server-side — so MCP
  ergonomics for long runs are a pre-existing, separate wrinkle.

- **E — Shutdown-aware admission + cancel-friendliness** (composable with A or B).
  Two cheaper half-measures: (1) once SIGTERM has arrived, **refuse to dispatch**
  still-QUEUED runs instead of starting work that cannot finish (today the orderly
  pool `shutdown()` *drains the queue*, i.e. may start a doomed deploy with seconds
  left — spec-016 accepted this; an opt-in long run makes it worse). (2) Lean on
  spec-026's cancellation seam (`RunStatus.STOPPED`, `RunService.cancel`,
  `SshExecutor.cancel(cancelKey)` closing the tracked `ChannelExec`): a *deliberate*
  pre-shutdown cancel of a long run is strictly better than an accidental sever — the
  row is terminal with its output retained, not `INTERRUPTED`-unknown. A "cancel
  in-flight runs, then restart the controller" affordance may capture much of B's
  value with none of the platform coupling.

## Open Questions

1. **The ceiling.** Which stance on the OS kill timeout: guidance-only, refuse-new,
   hard cap keyed to a declared platform timeout, or some combination? Is a boot-time
   sanity check even possible (the app cannot generally read its own
   `TimeoutStopSec`)?
2. **Declaration granularity (C).** Run-request-only, action default, or both — and
   if an action default, is it inside or outside `ActionSnapshot.hash` (i.e. does
   changing the drain posture reset approval)? What default would
   `LifecycleDiscoverer` propose for a discovered `deploy`?
3. **Drain mechanics.** Can the extended window be realised without abandoning the
   spec-016 shape (single pool, `awaitTerminationSeconds`, `@DependsOn` ordering
   before `MinaSshExecutor.shutdown()` stops the shared client) — or does per-run
   drain policy force a `SmartLifecycle` phase, the alternative spec-016 explicitly
   rejected as over-machinery?
4. **Eligibility (D).** Open to any run, or gated by action shape? And does MCP need
   any friction at all, given the flag is availability-only — or is a log line at
   shutdown ("waiting 300 s on run X, flagged via MCP by user Y") enough?
5. **Does E suffice?** If "refuse doomed dispatch during shutdown" + a visible
   in-flight marker + deliberate cancel cover the realistic incidents, B's platform
   coupling may never pay for itself. What real deploy durations do we see once
   spec-050 lands — seconds (fits 25 s) or minutes (doesn't fit *any* sane systemd
   stop timeout either)?
6. **Reconciler note fidelity.** Whatever is chosen, should `RunReconciler` record
   *which policy* the interrupted run had asked for ("flagged wait-on-shutdown but
   the window elapsed") so the post-mortem is honest about what was promised?

## Related

- **spec-050** — the driver: its "Long deploy vs graceful shutdown" residual is
  explicitly deferred to this concern; its no-daemonization constraint is why
  detaching the remote process is off the table here.
- **spec-016** — owns the drain being extended: `application.yml` graceful keys,
  `AsyncConfig` await + `@DependsOn` ordering, `RunReconciler`/`INTERRUPTED`.
- **spec-005** — the run engine (`RunService.run` single gate, `runExecutor`,
  `RunOutputHub`); the option must ride its one entry point.
- **spec-008** — the MCP `run_action` tool that would carry the flag; the gate
  invariant (`GateArchTest`: no `ApprovalService`, no repository in `mcp/`) is
  untouched by an engine-directive field.
- **spec-026** — the cancellation seam (`STOPPED`, `RunService.cancel`,
  `SshExecutor.cancel`) that Option E leans on; also the redeploy-as-`CUSTOM`
  convention that produces the long runs.
- **spec-007 / spec-004** — what a *param* is (argv-bound, hash-covered, gated) —
  the thing this option deliberately is not.
- **ARCH.md** — S7 (run quotas — the availability lever an MCP-set flag touches);
  S4 (why the flag must never become an argv token). The approval gate enforcement
  points are unaffected: this concern is about **availability during shutdown**, not
  about what is allowed to run.
