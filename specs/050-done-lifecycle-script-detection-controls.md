# 050 — Lifecycle-script detection & monitor controls

**Status:** done · branch `moacyrricardo/spec-050-lifecycle-script-detection-controls` · no Linear (blocked for this repo; tracked as
`spec-050`). **Stacked on spec-049** (app-folder detection) — this spec assumes the
port→PID→cmdline→cwd chain that resolves a running native app to its folder is
already in place and reuses its resolution seam.

## Context

A **systemd** app gets lifecycle controls from `SystemdDiscoverer` (spec-026:
status/restart/tail-logs keyed by the reserved `app-name` param) and a **docker** app
gets them from `DockerDiscoverer`. An **unmanaged** app — launched by hand or by a
`run.sh` from its own folder, the most common shape on small boxes — gets nothing:
no systemctl unit to restart, no container to stop. Yet its real lifecycle *is
already on the box*, as scripts sitting next to the app (`run.sh`, `kill.sh`,
`deploy.sh`, a `Makefile` with `start:`/`stop:` targets, a `package.json` `scripts`
block). Today nothing surfaces them: the operator SSHes in by hand, or manually
authors a `CUSTOM` action per script (exactly what the demo harness did via MCP —
registering `run.sh`/`kill.sh` as custom actions and approving them in the UI).

Everything needed to make that automatic already exists:

- spec-049 knows each app's **folder** (and PID).
- spec-007 defines the exact action shape a script wants: a `CUSTOM`-style action
  whose leading argv token is a `LITERAL` **absolute script path** (never a param),
  each argv element POSIX single-quoted by the SSH adapter (S4).
- spec-015 defines why "approve a `start.sh`" is already safe machinery:
  `ApprovalService.approve` `sha256sum`s the leading literal path
  (`ScriptPinService.probe`; unreadable → `ScriptUnreadableException`, 409) and
  `RunService.run` re-probes before every run (`ScriptModifiedException`, 409) — the
  bytes that run are the bytes a human approved.
- spec-026 defines how an approved mutating action reaches an app card: the reserved
  scalar `app-name` param (`ParamBinder.targetApps`), surfaced by `MonitorService`
  as `appOps` and rendered by `opsForApp`/`opsRunChip` in `app.js`.

This spec is the **unmanaged / script-launched counterpart of spec-026**: detect the
lifecycle scripts, *propose* them as gated actions, and render the approved ones as
start/stop/restart/deploy controls on the monitor app cards.

## Decision

Add a **`LifecycleDiscoverer`** (`discovery/service`, implements `RecipeDiscoverer`,
new `DiscovererFamily.LIFECYCLE` — default **on**, no capability note: its probes are
reads the login user already has, and proposals are gated like everything else). Per
native app it:

1. resolves the app's PID + folder (the spec-049 chain — shared helper, not
   re-derived here),
2. runs **one fixed, source-controlled scan script** on the target that combines the
   two detection signals below and emits **NDJSON** — one line per app:
   `{"appRoot": …, "managedBy": "script|systemd|docker|bare", "scripts":
   [{"path": …, "verb": …, "paramsHint": "none|args", "source":
   "ancestry|folder|build-file", "proposed": true|false, "selfBackgrounds":
   true|false|null}]}` — where **`proposed: false`** marks a build-file /
   Makefile / compose / Procfile entry that is *reported for context only* and is
   never turned into a runnable action (the "found scripts, never inferred
   run-commands" constraint), and `selfBackgrounds` is the review-only foreground
   hint (`null` when not inspected),
3. parses those lines into **proposed actions** — one `RecipeType.CUSTOM` recipe per
   app, named `lifecycle <app-name>`, every action `PENDING_APPROVAL` through the
   normal `DiscoveryService` path. **Detection only proposes; a human approves in
   the UI** (spec-004, UI-only, `GateArchTest`-enforced).
4. The UI maps approved lifecycle actions to **card controls** by action *name*
   (start/stop/restart/deploy). The verb is carried as nothing more than the action
   name — **no persisted classification model** (spec-040 thin-BE: discovery
   surfaces commands + params; the UI assembles the controls).

Scope rule: the discoverer proposes runnable actions **only for apps whose
`managedBy` is `script` or `bare`**. A systemd app already has spec-026 controls and a
docker app has `DockerDiscoverer`'s — this spec never creates a second way to restart
the same app (the spec-026 anti-goal).

**When a systemd-managed app also has folder scripts (decision):** the scan still
**reports** them in the NDJSON (`proposed: false`) so the UI can show a read-only note
*"lifecycle scripts present — managed by systemd"* on the card, but proposes **no**
runnable actions — spec-026's `systemctl` controls own that card. If both control sets
ever end up on one card (e.g. an operator hand-authored a script CUSTOM action for a
systemd app), the UI **dedups by verb, preferring the systemd control**, so a card
never shows two `Restart` buttons.

### Hard constraint: register found scripts, never inferred run-commands

The discoverer proposes an action **only for a lifecycle script that exists as a file
on disk** (`start.sh`, `stop.sh`, `deploy.sh`, …); it runs that file **verbatim** —
one absolute `LITERAL` path, nothing prepended, nothing appended.

- **No inferred run-commands.** The discoverer must **never synthesize** a way to run
  an app from build metadata — no `mvnw spring-boot:run`, no `gradlew bootRun`, no
  `npm run start`, no `make start`. Inferring "this is a Spring Boot app, so *this* is
  how you start it" is precisely the judgement this spec refuses to make: the inferred
  command is almost always **foreground**, which would force us to invent a
  backgrounding wrapper (`nohup … &`, `setsid`, `disown`) — and **fabricating a
  daemonization wrapper over SSH is the specific danger we are ruling out.** The
  discoverer never emits `nohup`/`setsid`/`&`/`disown` or any operator (S4 already
  bans operators in an argv; this bans us adding them at proposal time too).
- **Backgrounding is the script's own responsibility.** A `run.sh` we found either
  daemonizes itself or it does not — that is a property of the operator's own script,
  not something we infer or repair. We register the file as-is and run it as-is; if it
  runs foreground, that is the script's contract, surfaced honestly (below), not
  patched by us. *We find a self-managing script and register it, or we register
  nothing for that verb.*
- **Build files / Makefile / `package.json` / compose are detected but NOT proposed
  as runnable.** They are reported in the NDJSON (`source: "build-file"`,
  `proposed: false`) so the UI can note *"manual start: `make start`"* as read-only
  context, but they never become an approved, one-click card control. (Compose also
  falls to `DockerDiscoverer` by the scope rule.) This removes the entire class of
  inferred-command actions — and, as a bonus, every remaining proposed action is a
  direct script whose **own bytes** are content-pinned (spec-015), never a tool binary
  standing in for logic it cannot pin.
- **Self-backgrounding is review context, not a run-time inference.** The scan *may*
  `grep` a proposed script for `nohup`/`setsid`/`&`/`disown`/`systemd-run` and carry a
  boolean `selfBackgrounds` hint into the action description ("appears to background
  itself" / "appears to run in the foreground — Start will hold the run open"). It is
  shown to the human at approval and never changes how the script is executed.

## Detection

Two independent signals, combined in the one scan script. The script body is a Java
source constant (the `AppMonitorDiscoverer.PROCESS_PROBE_SCRIPT` idiom); the app's
PID and folder are its **only** positional arguments — discrete argv elements
(`sh -c <SCRIPT> sh <pid> <appRoot>`), single-quoted by the adapter, never
interpolated into the script text (S4). Both values are attacker-influenced (S3:
they come from `/proc` of a process an outsider may influence); the 004 human
approval of whatever gets proposed is the mitigation, as for every discoverer.

### By PID / process ancestry

Walk the PPID chain from the app's PID and read each ancestor's cmdline; classify
the runtime from the cgroup:

```sh
pid="$1"; root="$2"
# managedBy from the cgroup (same signal AppMonitorDiscoverer already reads):
#   *.service → systemd ; docker|containerd|kubepods → docker ; else script/bare
cat "/proc/$pid/cgroup"
# PPID walk — strip through the last ')' first: comm may contain spaces/parens.
p="$pid"
while [ "$p" -gt 1 ] 2>/dev/null; do
  tr '\0' ' ' < "/proc/$p/cmdline" 2>/dev/null; echo
  p=$(sed 's/.*) //' "/proc/$p/stat" 2>/dev/null | awk '{print $2}')
done
```

If an ancestor's cmdline is `<shell> <absolute path ending .sh>` (e.g.
`bash /opt/app/run.sh`), that script is the **launcher**: it (a) proves the app is
*not* a systemd service (the spec-026 territory ends here), (b) anchors the app
folder (its dirname corroborates spec-049's cwd-derived root), and (c) is itself the
strongest **start** candidate — emitted as a `scripts[]` entry with
`source: "ancestry"`, even when it lives outside `appRoot`.

### By app folder

Scan the spec-049 root for conventional lifecycle scripts and build files:

```sh
for f in start.sh run.sh stop.sh kill.sh restart.sh deploy.sh; do
  [ -f "$root/$f" ] && echo "script $root/$f"
done
[ -f "$root/Makefile" ] && grep -oE '^(start|stop|restart|deploy|run)[[:space:]]*:' "$root/Makefile"
ls "$root/pom.xml" "$root/mvnw" "$root/gradlew" "$root/build.gradle" \
   "$root/build.gradle.kts" "$root/Procfile" \
   "$root/docker-compose.yml" "$root/docker-compose.yaml" "$root/compose.yaml" 2>/dev/null
[ -f "$root/package.json" ] && grep -oE '"(start|stop|restart|deploy)"[[:space:]]*:' "$root/package.json"
# Param inference — honest and coarse: does the script consume arguments at all?
grep -qE '\$[0-9@*]|getopts' "$root/run.sh" 2>/dev/null && echo "args run.sh"
```

**Verb mapping — proposed (runnable).** A proposed action is *only ever* a lifecycle
**script file** the scan found; its argv is that one absolute `LITERAL` path, run
verbatim — **no arguments prepended or appended, no shell operators, no loops, no
free-form param, ever** (S4, spec-007, and the "found scripts, never inferred
run-commands" constraint above):

| Script file found                     | Verb    | Proposed argv (single `LITERAL`)     |
|---------------------------------------|---------|--------------------------------------|
| `start.sh` (else `run.sh`)            | start   | `<root>/start.sh`                    |
| `stop.sh` (else `kill.sh`)            | stop    | `<root>/stop.sh`                     |
| `restart.sh`                          | restart | `<root>/restart.sh`                  |
| `deploy.sh` (`update.sh`)             | deploy  | `<root>/deploy.sh`                   |
| ancestry launcher `…/run.sh`          | start   | `<launcher path>` (only if no folder start candidate won) |

Precedence when several script files map to one verb: the first row wins the verb
name; the losers are still proposed under their **basename** as the action name
(`run` for a losing `run.sh`) — runnable from the drawer, but not mapped to a card
button (name ≠ verb). Action names are unique within the recipe by construction.

**Detected but NOT proposed as runnable** (reported in the NDJSON with
`proposed: false` so the UI can show *"manual start: `make start`"* as read-only
context — never a one-click control):

| Artifact                              | Why not proposed |
|---------------------------------------|------------------|
| `Makefile` `start/stop/deploy` targets | running it means synthesizing `make -C <root> <target>` — an **inferred** command, and a target is typically foreground; also pins the `make` binary, not the logic |
| `pom.xml` / `mvnw`                    | `mvnw … spring-boot:run` is an inferred **foreground** starter — running it over SSH would require a daemonization wrapper we refuse to fabricate |
| `gradlew` / `build.gradle(.kts)`      | same as maven (`gradlew bootRun`, foreground, inferred) |
| `package.json` `scripts`              | `npm run <script>` is inferred + usually foreground |
| `docker-compose.yml` / `compose.yaml` | belongs to `DockerDiscoverer` (scope rule); and `compose up -d` is still a synthesized command |
| `Procfile`                            | free-form command lines — proposing them verbatim widens the S3/S4 surface far beyond a fixed literal path |

**Param inference — be honest.** The safe and common answer is "no params": every
lifecycle action is proposed **param-less** (the bare absolute path / fixed argv).
`paramsHint: "args"` (a `$1`/`$@`/`getopts` hit) is carried only into the action
`description` ("this script reads arguments — edit to add typed params before
approving"); the discoverer **never fabricates typed params** from a heuristic, and
a free-form param is forbidden outright (S4). An operator who needs a param edits
the action (which resets it to `DRAFT`, per the 004 machinery) and declares one.

## Actions, the gate & content-pinning

- **Shape.** Each proposed action is a spec-007 CUSTOM-style action: leading
  `LITERAL` absolute path, optional further `LITERAL`s (the `make -C <root> start`
  vector), `sudo = false` (the folder came from the login user's own process; there
  is nothing to escalate). Plus one **reserved scalar `app-name` param**,
  `ALLOWED_SET` of size one — the owning app — validated against
  `ParamBinder.APP_NAME_PATTERN` (spec-026). It is a correlation-only param (no
  `PARAM` argv token references it; `ParamBinder.bind` demands values only for
  tokens, and `targetApps` reads the def) — exactly the spec-026(d) redeploy shape.
- **Recipe.** One proposed recipe per app: `RecipeType.CUSTOM`, name
  `lifecycle <app-name>`. Choosing `CUSTOM` (not a new enum) is deliberate:
  `ApprovalService.approve` keys **content-pinning on
  `recipe.type == RecipeType.CUSTOM`**, so approving any of these actions
  automatically `sha256sum`s the leading literal path over SSH
  (`ScriptPinService.probe`) and binds `approvedScriptHash`; an unreadable path
  refuses approval with `ScriptUnreadableException` (409, action left
  `PENDING_APPROVAL`); every run re-probes and refuses drift with
  `ScriptModifiedException` (409). This intentionally flips spec-015's
  "discovered actions are not pinned" note — these discovered actions land under
  `CUSTOM` precisely so they **are** pinned, with zero new pinning code.
- **Review context (decision).** The scan carries a short read-only **preview** of
  each proposed script — its first comment block / `head -n 5` — into the action
  description, and the spec-044 review drawer surfaces it beside the pinned path. It
  is context for the human's approval judgement only (an ambiguous `run.sh` /
  `kill.sh`), never parsed or executed. `selfBackgrounds` (above) rides here too.
- **The gate is untouched.** Proposals persist `PENDING_APPROVAL` through
  `DiscoveryService` (reconciled idempotently by `(machine, CUSTOM,
  "lifecycle <app>")`, spec-021: approved actions are never silently re-shaped —
  drift surfaces as `DIFFERS_AWAITING_REAPPROVAL`). Approval remains **UI-only**:
  no approve/run additions to the `mcp/` surface, no auto-approval anywhere, and
  `GateArchTest` (`noMcpClassReferencesApprovalService`, `noMcpToolIsAnApproveTool`)
  must pass unchanged. Never touch any `*ArchTest`.

## Monitor controls (UI)

No server change is needed to surface them: `MonitorService` already enumerates
APPROVED actions carrying the reserved `app-name` param into `appOps`, and
`opsForApp(machine, appName)` already matches them to a card. This spec changes how
the **UI renders** lifecycle-named ops (`app.js` only):

- **Card controls.** On the monitor app card / consumer drawer, approved,
  un-drifted (`!changedSinceApproval`) ops actions named exactly
  `start` / `stop` / `restart` / `deploy` render as a **lifecycle control row**
  (`Start` · `Stop` · `Restart` · `Deploy` buttons) instead of generic run chips.
  The name→button mapping lives client-side — the spec-040-consistent place for
  classification. All other ops keep the existing `opsRunChip` behaviour.
- **Run semantics.** `Start` (param-less, `app-name` bound implicitly to the card's
  app) runs **inline** — the spec-024 pattern (`runAndCollect`-style POST, output
  streamed into the drawer) — then triggers a monitor refresh so UP/DOWN reflects. If
  the script's `selfBackgrounds` hint is false, the run stays open while the script
  holds the foreground; the drawer says so ("foreground — Start holds this run open;
  cancelling it stops the app") rather than the UI pretending it completed.
- **Long-running runs (`deploy`/`update`).** A deploy or update script routinely runs
  for **minutes** (build, pull, migrate, restart). This is a property of the *run*, and
  is **unrelated to the 5 s monitor poll** — the poll cadence never bounds a run. The
  constraint is that **no short per-command SSH/exec timeout may cut it off**: the run
  executes on the ordinary async streaming path (spec-005) with **no truncating
  deadline** (or a generous, operator-visible one), output streamed live into the
  drawer, terminal state driven by the channel's own exit — not a timer. Interactions
  to honour: graceful shutdown (spec-016) drains in-flight runs within a **bounded
  25 s window** on SIGTERM — a deploy that outlasts it is severed with the app's SIGHUP
  and reconciled to `STOPPED`/`UNKNOWN` on next boot (the **A + guard rails** baseline
  under Resolved design questions; an opt-in *extended* drain is deferred to concern
  spec-051); the run stays cancellable (spec-026 cancellation) so a wedged deploy can
  be stopped deliberately, but nothing stops it automatically.
- **Destructive guard (spec-044).** `Stop`, `Restart` and `Deploy` are destructive:
  they must route through the **review/confirm drawer** before running — the
  spec-044 `renderActionReview` body (exact pinned command, params, approval state)
  plus an explicit confirm button, mirroring the review-safety guard that already
  forces first-approvals through the drawer. One-click destruction is never offered.
- **`Deploy` carries extra friction (decision).** On top of the confirm drawer,
  `deploy` requires the operator to **type the app name to confirm** (GitHub-style):
  the drawer shows a text field and the Deploy button stays disabled until the typed
  value equals the card's app name. `stop`/`restart` keep just the drawer; only the
  longest, most destructive verb gets the name-typing gate. (A separate second
  approval was considered and not chosen — the type-to-confirm is friction at the
  moment of danger without adding a second gate to administer.)
- **Restart composition (decision).** `Restart` renders whenever a real restart
  action exists **or** both `start` and `stop` do. With a real `restart.sh` /
  `restart` target it runs that directly. Otherwise the UI **composes** it: run
  `stop`, await its **terminal** run state, then run `start` — sequenced client-side,
  behind the confirm drawer, with an explicit warning that *"if start fails after
  stop succeeds, the app stays down"*. The two runs are separately audited; the drawer
  makes the half-failure risk visible rather than hiding the compose. If `stop`
  reaches a failed terminal state, `start` is **not** auto-run (the drawer reports the
  stop failure and stops there).
- Approval itself happens on the machine page exactly as today (spec-044 split
  button + drawer); the monitor never approves.

## Permissions & coverage

Same honesty as spec-049 and `AppMonitorDiscoverer`:

- `/proc/<pid>/stat|cmdline|cgroup` ancestry reads and reading a script's text for
  param inference require **same-user (or root)** access. The probe runs as the
  machine's `loginUser`, no `sudo` — so coverage is bounded to apps that user owns;
  other users' apps are invisible, by design (the documented S5 gap).
- `sha256sum` must exist on the target and the script must be readable by the
  login user at approval **and** at run (spec-015 requirement — approval needs the
  box reachable).
- A folder the login user cannot list simply yields no proposals — never an error,
  never a privileged retry.

## Resolved design questions (review 2026-07-23)

- **Restart composition — composed, guarded.** `Restart` shows when a real restart
  action exists *or* both `start`+`stop` do; with only start+stop the UI runs
  `stop → await terminal → start` client-side behind the confirm drawer, warns that a
  half-failure leaves the app down, and does **not** auto-run `start` if `stop` fails.
  (See Monitor controls.)
- **Deploy safety — type-the-app-name.** `deploy` requires typing the app name in the
  confirm drawer (GitHub-style) on top of the review; `stop`/`restart` keep just the
  drawer. Second-approval option not chosen.
- **spec-026 overlap — report, don't propose.** A systemd-managed app's folder scripts
  are reported (`proposed: false`, read-only note) but never proposed as runnable;
  the UI dedups by verb preferring the systemd control. (See Scope rule.)
- **Ambiguous script→verb — carry a preview.** The proposal carries the script's first
  comment block / `head -n 5` as read-only review context in the drawer. (See Review
  context.)
- **Param inference — hint only, no new UI.** The description hint ("reads arguments")
  is enough for v1; declaring a typed param uses the existing edit-action flow (which
  resets the action to `DRAFT`). No bespoke "edit params in the drawer" affordance.
- **049 seam — shared resolver.** `LifecycleDiscoverer` reuses a shared `appRoot(pid)`
  resolution helper extracted from 049 rather than re-deriving; exact shape settles
  when 049 lands.
- **Long deploy vs graceful shutdown — A + guard rails; policy deferred to spec-051.**
  V1 baseline: keep spec-016's **bounded 25 s drain** (shutdown never stalls). A deploy
  still running at force-close is severed with the app's SIGHUP and the boot reconciler
  marks the orphaned run `STOPPED`/`UNKNOWN` with a controller-restart note; the deploy
  control shows a **deploy-time warning** ("runs attached — restarting compute-admin
  will interrupt this") and sets an **in-flight-deploy marker** an operator's own
  restart tooling can check. No drain extension and no detaching the run (detaching =
  the daemonization inference this spec forbids). The richer choice — letting a run
  **opt into an extended drain** ("wait until finishes") — is a cross-cutting
  run-execution concern, not deploy-specific (a DB backup or long `tail` has the same
  need), and is **split out to concern spec-051** (run execution policy). 050's deploy
  control simply consumes whatever policy 051 lands; the default stays the bounded
  drain, so 050 does not block on it.

## Open Questions (residual)

**None open for 050.** The long-running-drain *policy* is deferred to **concern
spec-051** (run execution policy: long-running / wait-on-shutdown opt-in); 050 ships on
the bounded-drain baseline above regardless of what 051 decides.

## Known Gaps

- **Every proposed action pins real bytes.** Because build-tool / `Makefile` /
  `package.json` invocations are detected-only (never proposed), there are no
  tool-led argvs among proposed actions — the leading literal is always a lifecycle
  **script file**, so spec-015 always pins the bytes that actually carry the
  lifecycle logic. (This is the flip side of the constraint: refusing inferred
  run-commands also removes the "pins the tool, not the logic" pinning hole.)
- **A found script that runs foreground holds the run open.** A `run.sh` that does
  not self-background keeps its run RUNNING while the app lives, and cancelling that
  run closes the SSH channel and stops the app. This is the script's own contract,
  surfaced via `selfBackgrounds` at approval and in the drawer — not repaired by us
  (repairing it would mean the daemonization inference the constraint forbids).
- **Name collision with user CUSTOM recipes.** Reconciliation keys on
  `(machine, CUSTOM, "lifecycle <app>")`; a user-authored CUSTOM recipe with that
  exact name on that machine would be adopted by discovery's reconciliation. The
  `lifecycle ` prefix convention makes this unlikely; not structurally prevented.
- **Foreground starters hold the run open** (Open Question 4) — proposed, but the
  run-view Stop kills the app, not just the stream.
- **Verb heuristics are conventions.** A `stop.sh` that does something else entirely
  is proposed as `stop`; the approval review (path + pinned bytes + description) is
  the mitigation, same as every discoverer's S3 posture.
- **No `Procfile` proposals** (free-form command lines) — detected and reported
  only.

## Related

- **spec-049** — app-folder detection (the stacked dependency: PID + `appRoot`).
- **spec-026** — the managed-service counterpart this spec mirrors
  (`SystemdDiscoverer`, the reserved `app-name` facade, run cancellation).
- **spec-007 / spec-015** — the CUSTOM script-action shape and the content-pinning
  machinery this spec reuses verbatim (`ScriptPinService`,
  `ScriptUnreadableException` 409, `ScriptModifiedException` 409).
- **spec-024 / spec-044** — inline runnable drawer actions; review drawer +
  split-button + review-safety guard (extended here to destructive lifecycle verbs).
- **spec-040** — the thin-BE leaning honoured: discovery proposes commands+params;
  verb→control assembly is client-side; no persisted classification model.
- **ARCH.md** — gate enforcement points; **S4** (typed params, quoted argv, no
  free-form commands); **S5** (login-user coverage bound; no new sudo).
- specs/021 (reconciliation), 006 (discoverer contract), 035 (family enablement).

## Implementation Notes

Built on branch `moacyrricardo/spec-050-lifecycle-script-detection-controls`, **stacked
on #72** (spec-049); PR #73 bases on the 049 branch and must merge after it.

- **`LifecycleDiscoverer`** (`discovery/service`, `RecipeDiscoverer`, family
  `DiscovererFamily.LIFECYCLE` default-on). One fixed `SCAN_SCRIPT` per app whose only
  positional args are `<pid> <appRoot>` (S4). It emits the spec's NDJSON
  (`{appRoot, managedBy, scripts:[{path, source, proposed, selfBackgrounds, paramsHint,
  preview}]}`); Java maps verbs, applies precedence, the scope rule, and builds the
  proposals. Verb→proposed mapping and precedence live in Java (not the shell), which
  keeps the scan a dumb, source-controlled detector.
- **No migration.** `DiscovererFamily.LIFECYCLE` persists via the existing
  `discovery_enablement.family VARCHAR(20)` free-string column (no CHECK constraint); the
  049 base already widened `arg_token`/`blueprint_arg_token` to `VARCHAR(16384)` so a
  large scan-script token fits. Nothing new was added to the schema.
- **Correlation-param fix (post-merge-eval).** The spec assumed the reserved `app-name`
  needed no validation change ("same as spec-026"). It did: `ActionService.applyStructure`
  requires every declared param to be referenced by a token, and spec-026's app-ops pass
  only because their command *references* `app-name`. A lifecycle action's argv is a lone
  literal script path, so its correlation `app-name` is unreferenced and discovery failed
  with `Param declared but never referenced: app-name`. Fixed by exempting the reserved
  scalar `app-name` (ALLOWED_SET) from the referenced-requirement — it is a dashboard
  correlation label, not a command input; every other unreferenced param is still
  rejected. Regression: `LifecycleAppNameCorrelationTest` (drives the exact
  `addAction`→`applyStructure` path the pure-discoverer test skipped).
- **049 seam (OQ6) — deviation.** 049 landed its resolution embedded in a monolithic,
  du-oriented, port-driven shell probe (`FOOTPRINT_PROBE_SCRIPT`, run-path fan-out), not a
  cleanly extractable helper. Rather than a risky rewrite of the unmerged parent, 050
  resolves pid+appRoot **Java-orchestrated over `ss`/`/proc`** (the same port→PID→cwd/jar-dir
  chain `AppMonitorDiscoverer` uses for naming) — one resolution path within 050, parent
  untouched. A future "extract one shared resolver" refactor is a reasonable follow-up.
- **Correlation** keys the reserved `app-name` on the **spec-049 folder identity**
  (appRoot basename — which 049 calls "what spec-050 keys to"). Where the monitor card's
  name diverges (a well-named jar, a process-named generic), the ops surface on the machine
  page but not the card — the same imperfect-correlation posture spec-026's facade ships
  with (see Known Gaps).
- **UI** in `app.js` only; **no `MonitorService` change** (`appOps` already enumerates any
  approved reserved-`app-name` action, any recipe type). Approved `start/stop/restart/deploy`
  render as a control row; Start is inline (spec-024), the destructive verbs route through
  the spec-044 confirm drawer, Deploy adds the type-the-app-name gate + long-running
  warning, Restart composes stop→await-terminal→start when no real restart exists, and a
  `SYSTEMD` control wins the verb over a script one (dedup). `selfBackgrounds`/`preview` ride
  the action description; the UI reads the foreground hint from it.
- **systemd read-only note deferred.** With no server change permitted and no persistence
  channel for a note-without-action, a systemd/docker-managed app is simply not proposed
  (scope rule honored); build-file context does ride the recipe description. The bounded
  25 s shutdown-drain baseline is the deploy policy; the extended-drain opt-in stays in
  concern spec-051.
- **Tests:** `LifecycleDiscovererTest` (7) + `lifecycle-controls.render-check.js`. Full
  suite `mvn -q -B clean verify` green — 292 tests, 0 failures; every `*ArchTest`
  (`GateArchTest`, `MachineEventArchTest`) unchanged and green.
- **Change division (`CONTRIBUTING.md`).** Commits: status flip → backend discoverer →
  UI → tests → this doing→done closeout, one logical concern each. Per the run directive
  the opening flip combined the rename with the 1-line header edit (CONTRIBUTING §3 prefers
  isolated renames; spec-049 split them) — minor, not rewritten. No enabling refactor and
  no migration were required, so no pre-PR stack.

## Acceptance

On a machine running a native app launched by `bash /opt/app/run.sh` (with
`kill.sh` beside it — the demo-harness shape): running discovery proposes a
`lifecycle <app>` CUSTOM recipe with a param-less `start` (`/opt/app/run.sh`) and
`stop` (`/opt/app/kill.sh`), both `PENDING_APPROVAL` and both carrying the app's
reserved `app-name` param. Approving them **in the UI** pins each script's SHA-256
(an unreadable path refuses with 409 `script_unreadable`). The monitor app card then
shows **Start** and **Stop** controls: Start runs inline and the card flips UP;
Stop opens the confirm/review drawer showing the exact pinned command before
running. Editing `run.sh` on the box after approval makes the next Start refuse
with 409 `script_modified` until re-approved. `GateArchTest` and every other
`*ArchTest` pass unchanged; nothing new is reachable from `mcp/`.
