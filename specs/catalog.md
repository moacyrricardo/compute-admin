# Spec catalog

The architectural decision record for compute-admin. Each feature lands as a
numbered spec (`NNN-status-slug.md`, status `todo`/`doing`/`done` by file rename;
an exploratory **concern** uses the `concern` token instead — note that concerns
017/027/031/036/040/051 predate that and are still filed as `todo`),
authored with `/new-spec`, grounded in [ARCH.md](../ARCH.md) for architecture and
[CONTRIBUTING.md](../CONTRIBUTING.md) for build/commit conventions and code style
(the build charter, mirroring `birthday-rsvp`). Linear is
**blocked** for this repo — specs carry no issue id and commits use `spec-NNN`.

Filename status is the source of truth once merged to `main`; the **Status** column
below also reflects in-flight PRs (a spec's file reads `todo` on `main` until its PR
merges and renames it).

## Specs

| # | Spec | Status | Notes |
|---|------|--------|-------|
| 001 | Project skeleton | ✅ done | on `main` |
| 002 | MCP transport seam | ✅ done | on `main` |
| 011 | User accounts, authentication & ownership | ✅ done | on `main` — auth **mechanism** superseded by 014 (email+password); JWT/tokens/pairing/ownership still authoritative |
| 003 | Machine registry, tagging, app keypair & SSH adapter | ✅ done | on `main` |
| 004 | Recipe & Action model, approval gate & audit | ✅ done | on `main` — the security core |
| 005 | Execution engine (async jobs + live streaming) | ✅ done | on `main` |
| 006 | Recipe auto-discovery | ✅ done | on `main` |
| 007 | Custom-command recipes | ✅ done | on `main` — groups multiple custom actions per recipe |
| 010 | Recipe blueprints (author once, instantiate per-machine) | ✅ done | on `main` |
| 008 | MCP write & run tools | ✅ done | on `main` — MCP actor-propagation resolved |
| 012 | Web UI shell, design system & the approval screen | ✅ done | on `main` — live-integrated |
| 013 | Runtime resource hygiene (H1/H3/H6) | ✅ done | on `main` — streaming eviction, tx scoping, one shared SSH client |
| 014 | Email + password authentication | ✅ done | replaces Google sign-in; supersedes 011's auth mechanism |
| 015 | Custom-script content-pinning | ✅ done | on `main` — security: hash-at-approval + re-hash-at-run; resolves **H5**, hardens S5 |
| 016 | Graceful shutdown & run reconciliation | ✅ done | drain in-flight runs + boot reconciler for orphaned QUEUED/RUNNING rows; neighbor to S7 (out of scope) |
| 017 | Transaction-boundary strategy | ⚪ todo | **concern** (exploratory, options open) — `TransactionTemplate` (A, as-built) vs bean-refactor (B) vs `@Async`+future (C) for "I/O outside tx, persist in a short tx" |
| 018 | Machine tags: filtering & auto-tagging | ✅ done | filter machines by tag; auto-tag from login-user + OS/cloud probe |
| 019 | Event-driven connectivity status | ✅ done | `MachineReached` event + async listener updates status (fixes stale UNREACHABLE pill); manual test-connection |
| 020 | Machine monitoring | 🟢 graduated | **umbrella concern of record** — resolved into specs 021–026 + 029 (build order below); keeps the problem framing + the Q1/Q2 decisions |
| 021 | Discovery idempotency | ✅ done | resolves **H2** — re-discovery reconciles by `(machine, type, name)` instead of duplicating; refresh DRAFT/PENDING proposals in place, surface a diff on APPROVED-differs; uniqueness guard. **Monitoring prerequisite (build first)** |
| 022 | Monitoring foundations | ✅ done | the decisions spec — `RecipeType.MONITOR` (display-only, gate unchanged); `appName`(+`runtime`) label convention + double-detection link; `APP_PORT_LIST` param + **fan-out run mode** (S4-safe: fixed template per item, never a shell loop); run-row pruning (extends 013 eviction) |
| 023 | `monitor machine` recipe | ✅ done | universal read-only host vitals — cpu (`top -bn1`), ram+swap (`free -m`), disk (`df -h`); auto-proposed on every reachable box; no app param (→ host panel) |
| 024 | Monitor UI dashboard | ✅ done | enumerates `MONITOR` actions → host panel + per-app cards (framework badge, UP/DOWN pill, run-chip row) + detail drawer (Runtime block, related actions runnable inline, gate-safe); client-side poll single/5s/30s/1m/5m; theme-aware, textContent-only (012) |
| 025 | App-monitor recipes | ✅ done | `springboot`(actuator + process supplement)/`fastapi`(process + optional `/openapi.json`·`/metrics`)/`generic`(process-only) — discovery-routed via `ss -ltnp`→PID→cmdline classifier, pre-filled `(app-name,port)`, container name recovered from `/proc/<pid>/cgroup`; login-user only (S5) |
| 026 | App-ops recipes | ✅ done | **reserved `app-name` param** correlates ops to app cards (NOT tags/labels, NOT a new recipe class); `SystemdDiscoverer` (`RecipeType.SYSTEMD`) mirrors docker; bounded `tail-logs` + **follow-mode via new run cancellation** (`RunStatus.STOPPED`, `RunService.cancel`, `SshExecutor` channel-close seam, `POST /runs/{id}/cancel`, Stop control); redeploy stays `CUSTOM`/blueprint |
| 027 | Signal-driven machine unreachability | ⚪ todo | **concern** — the going-**OFFLINE**/UNREACHABLE counterpart to 019's instant going-**ONLINE** event: flip faster than the 5-min cron **without flapping** (leaning: a connect-failure triggers an immediate authoritative confirmation probe, not a direct flip). Builds on 019 (PR #30) |
| 028 | Machine name & MCP identity hardening | ✅ done | on `main` — **security** (ARCH **S9**): MCP identifies machines by `id`+user-provided `name`, hides `host`/`port`/`loginUser`; adds a required per-owner-unique **name** at registration; splits the MCP view (`id/name/tags/status`) from the full UI view; `register_machine` still takes host as input but stops echoing it |
| 029 | Fleet monitoring dashboard | ✅ done | fleet view — per-machine sections, tag + app-name filters (filtered-out = unpolled), a synthetic `no-apps` host-only view, per-app **mem-% of host**, unified per-app cards (checks + ops), new read `GET /api/runs/{id}/children`; folds in the spec-025 actuator-liveness → `http app monitor` fallback |
| 030 | Docker container monitoring | 🟢 resolved | **concern** — graduated into specs **032–035** (2026-07); stays the problem framing, the *how* lives there |
| 031 | Deferred follow-ups triage | ⚪ todo | **concern** (options open) — consolidates every deferred implementation note + spec-eval finding across built specs and merged PRs (#38/#39) into one worklist; each item re-asked **keep / drop / already-addressed**. Overlaps but does not replace the H-backlog below (see **H8**) |
| 032 | Monitoring axes foundations | ✅ done | the **consumer contract** for RAM/CPU/disk-as-%-of-host: `MonitorConsumerView` (role/source/dedication/owner/usedBy/bucket) + app-level CPU metric-kind; extends 022; prereq for 033/034. Folds in the **H8** cleanup (single source of truth per axis) |
| 033 | Docker container discovery | ✅ done | docker-native discovery — **compose project = app** (`com.docker.compose.project` label), datastore classification by image, `docker stats`/`ps -s`/`system df -v` metrics, springboot-in-docker shown once. `RecipeType.MONITOR`, gate untouched. **Resolves concern 030**; gated by 035 |
| 034 | Fleet monitor UI/UX redesign | ✅ done | segmented tri-axis machine bars (one colour per consumer), all-three-axes cards, the **databases lens** (Dedicated owner-split / Shared used-by, one lens two bands), hidden docker/system buckets, categorical palette token group. spec-012 idiom; ref [`docs/fleet-resource-mock.html`](../docs/fleet-resource-mock.html). Builds on 029 + 032 |
| 035 | Discovery enablement & UX | ✅ done | **per-family** discovery enablement (Docker/Systemd/Database…), **docker off by default** (socket = root-equivalent); a machine "Discovery" panel. Enablement ≠ the approval gate. **Resolves 030 doubt (1)**; gates 033 |
| 036 | Recipe & param discovery lifecycle | ⚪ todo | **concern** (options open) — lifecycle *beyond approval*: re-discovery adds/refreshes but never **retires** a vanished resource (lingers, incl. runnable APPROVED); no delete/hide/**suppress** (revoke is the only stop); fan-out lists are all-or-nothing and discoverers **flood** (all systemd units / all containers). Keyed on the gate-safety asymmetry: narrowing `APP_PORT_LIST` is gate-free, but the `app-name` ALLOWED_SET is hashed |
| 037 | Docker consumer metric polling | ✅ done | fills the docker axes live — the param-free `docker stats`/`ps -s`/`system df -v` reads parsed client-side and normalized to % of host (RAM ÷ total, CPU ÷ nproc, disk ÷ data-root FS); adds the `nproc` **`cores`** host vital as the CPU denominator. Follow-up to 034 |
| 038 | Compose-project grouping | ✅ done | one card per compose project — a project's datastores render as `services[]` of a single APP consumer (not scattered dedicated cards), and the Databases lens derives its Dedicated band from those services. Fixes the 033 "not composing" display |
| 039 | Native consumer CPU axis | ✅ done | fills the native app CPU axis — `applyConsumerReading` parses the process-tree `%CPU` (÷ host cores, mirroring docker) so native apps show CPU instead of "no data". Disk stays `—` for native (no attributable footprint) |
| 040 | Monitor as a runtime view over runs & model weight | ⚪ todo | **concern** (options open; leaning = **thin BE**) — monitor polls run through `POST /runs` but are invisible (Runs UI is a `localStorage` log; no server run-**list** endpoint), and a classification taxonomy (032 enums, 033/038 grouping) accreted server-side though only discovery-over-SSH + the gate genuinely force it (**S9 does not**). Options: surface runs (B) / thin toward UI-or-BFF assembly (C, the leaning) / backend read-model (D). Revisits 032/033/038 |
| 041 | Host system/other usage segment | ✅ done | on branch `moacyrricardo/spec-041-host-system-usage-segment` (PR #56) — shows **real** RAM/CPU/disk on app-less machines: polls host vitals' **used** (was discarded), renders an **OTHER/system** segment = `host_used − Σ attributed` (clamped ≥0, absent vital ⇒ —) so a bare box no longer looks idle, keeps `total − used` as the hatched free tail. Client-side (spec-040 leaning); needs `monitor machine` vitals. Concrete driver for **040** |
| 042 | Blueprint authoring UI (command-builder form) | ⚪ todo | completes spec-010's UI: today you can create/list/instantiate blueprints but **cannot add actions** (no command-authoring form exists anywhere — `renderCommand` only *displays*), so a UI-authored blueprint is permanently empty & instantiates to zero-action recipes. Build a reusable argToken/paramDef **command-builder** + add/edit-action + edit-blueprint + fix instantiate target selection; reusable by custom-recipe-action authoring |
| 043 | Mobile-responsive UI | ✅ done | on branch `moacyrricardo/spec-043-mobile-responsive-ui` (PR #61) — makes the web UI usable on phones (≈360–430px) + tablets by **extending** the existing tokens/CSS (no framework; keeps `h()`/textContent-only + both themes). Adds `--bp-sm 480`/`--tap-min 44px`, a collapsible **Menu** nav (JS toggle, `aria-expanded`, closes on route change), single-column stacking of `.app-cards`/`.row-between`/monitor controls, the consumer drawer as a slide-up bottom sheet, ≥44px touch targets, no sideways scroll. Presentation only; gate/data untouched, desktop >720px unchanged |
| 044 | Action approval UX | ✅ done | on branch `moacyrricardo/spec-044-impl-action-approval-ux` (stacked on the catalog-hygiene PR) — speed up the recipe/action surface (UI only, gate untouched): review/approve in the **monitor-style drawer** instead of a full-page round-trip; a **split button** per action (default = primary transition, caret menu = other valid verbs + "see more" → drawer), with a **review-safety guard** (first-approval / `changedSinceApproval` must open the drawer first); actions in a **2–3-up responsive grid** (1 col on phones, 043 breakpoints); **name-first** machine identity everywhere + a **copy-host** button. Reuses `openConsumerDrawer`/`copy()`/`act(verb)` |
| 045 | Architecture cleanups | 🟢 resolved | **concern** — researched four cleanups (exception mappers, DTO/Response, yaml, packaging), recommendation accepted; **graduated into 046/047/048** (2026-07). Stays the research + rationale of record |
| 046 | Unified error model | ✅ done | from 045 §1+§2: a shared `AppException extends WebApplicationException` (+ typed `ErrorResponse`) that carries its own `Response`, so the **19** `*ExceptionMapper` classes (521 lines) are **deleted**; wire format unchanged. Matches the existing 400 `BadRequestException` path; the 59 direct 400 throws + a clean service↔web boundary are deferred. ≈ −18 classes / −450 lines |
| 047 | Config to `.properties` | ✅ done | from 045 §3: converted the 3 YAML config files (65 lines) to `.properties`; keys/behaviour identical; pre-check found no profile-groups/multi-doc. **XS** |
| 048 | Release pipeline | ✅ done | on branch `moacyrricardo/spec-048-release-pipeline` — from 045 §4: a `v*`-tag GitHub Release workflow (build → jar + sha256 asset), a stable `<finalName>compute-admin`, and a README **Download & run** (`java -jar`) section. The uber jar already builds via the spring-boot plugin. **S**; highest external value |
| 049 | App-folder & footprint detection | ✅ done | **superseded → 054** (never built; reframed by the lightweight app-metadata model). — extends `AppMonitorDiscoverer`'s port→PID→cmdline chain with a fixed read-only `footprint` probe (the spec-032 `cpu`-check idiom — constant `sh -c`, validated port as sole `$1`, `APP_PORT_LIST` fan-out, S4-safe) emitting **one NDJSON line per port**; the script walks the target FS and `app.js` assembles a **folder + 3 distinct sizes** (artifact `stat` · data grow-dir · `du` footprint — the last **only** on deployed roots, build trees suppressed) onto the app card + drawer, filling the native disk story spec-039 left `—`. Detector table: **Java** (jar→`target/`pom / `build/libs`-gradle walk, standalone-dir = deploy) + **Python** (cmdline-shape / venv `pyvenv.cfg`·`VIRTUAL_ENV` / marker walk-up; honest `cwd-only`/`unresolved`); language-agnostic schema (node/go stubs). Thin-BE (040): no table / no new `RecipeType` / no DTO. Gate untouched; paths stay off the MCP surface (S9). `du` is **sampled** on the slow poll tier (not every 5s). Deployed apps **feed the native disk-% axis** (card size line kept too) — a **joint edit to spec-041**'s `computeOther` (subtract the newly-attributed native disk from OTHER). Python deploy floor = marker **or** venv resolves; symlinked releases show the symlink as identity. **spec-050 stacks on this** |
| 050 | Lifecycle-script detection & monitor controls | ✅ done | **superseded → 054** (never built; identity half → 054, verb half → 053). — **stacked on 049** — the unmanaged/script-launched counterpart of spec-026: a `LifecycleDiscoverer` (new `DiscovererFamily.LIFECYCLE`, default-**on**) resolves PID+folder (049), then one fixed scan script combines **PPID-ancestry** (`bash …/run.sh` launcher, cgroup `managedBy`) + **folder scan** → proposes **one `RecipeType.CUSTOM` recipe per app** (`lifecycle <app-name>`), every action `PENDING_APPROVAL`. **Hard constraint: register found script *files* (`start.sh`/`run.sh`/`stop.sh`/`kill.sh`/`restart.sh`/`deploy.sh`) and run them verbatim — never *infer* a run-command** (`mvnw spring-boot:run`/`gradlew bootRun`/`npm start`/`make`) and never synthesize a `nohup`/`setsid` backgrounding wrapper; build files/Makefile/compose/Procfile are **detected-only** (`proposed:false`, read-only context). Backgrounding is the script's own contract (surfaced via a `selfBackgrounds` review hint). `deploy`/`update` runs are expected **long-running** — no short exec timeout (unrelated to the 5s poll); v1 keeps spec-016's bounded 25s shutdown drain + guard rails (interrupted run reconciled, deploy-time warning, in-flight marker), the opt-in *extended-drain* policy split out to **concern 051**. Picking CUSTOM is load-bearing: `ApprovalService`/`RunService` key **spec-015 content-pinning** on `type==CUSTOM`, so scripts pin at approve + re-verify at run with **zero new code**. UI-only render (`app.js`; reuses spec-026 `appOps`/`opsForApp`, no server change): approved `start/stop/restart/deploy` ops become **card controls** — Start inline (024), Stop/Restart/Deploy via the **spec-044 confirm drawer** (`deploy` adds a type-the-app-name gate; `Restart` composes `stop→await→start`, guarded, when there's no `restart.sh`). Systemd-managed apps with folder scripts are **reported, not proposed** (026 controls win; UI dedups by verb). Params never fabricated (`paramsHint` → description only, S4). Scope: proposes only for `managedBy` `script`/`bare` (systemd→026, docker→`DockerDiscoverer`). Gate + `mcp/` + `*ArchTest` untouched |
| 051 | Run execution policy: long-running / wait-on-shutdown opt-in | ⚪ todo | **concern** (options open; leaning = **A stays default**) — split out of spec-050's long-deploy residual. A run that outlives spec-016's bounded drain (`server.shutdown: graceful` + `timeout-per-shutdown-phase 25s`; `ca.run.shutdown-await-seconds` 20 in `AsyncConfig`) is severed because the controller owns the SSH exec channel (`MinaSshExecutor`) → remote SIGHUP mid-run, `RunReconciler` marks the orphan `INTERRUPTED` (`exitCode=-1`). Explores a run-level **execution *option*** (NOT a spec-007 argv param — never reaches the target, outside `ActionSnapshot.hash`, no S4 surface; rides `POST /runs` beside `params` + one `run_action` MCP field into the single `RunService.run` gate — MCP carrying it is no gate bypass). **Hard ceiling, stated plainly:** the app can't out-wait systemd `TimeoutStopSec` / k8s grace → SIGKILL, which is *worse* than today's SIGHUP; so "wait until finishes" only ever means **extended *bounded* drain + operator raises the platform timeout to match**. Candidate default under discussion: a **90s** deploy run window (vs the ~25–30s baseline), which requires the platform stop-timeout raised to match. Detaching the remote (nohup/setsid) excluded (spec-050's forbidden inference). Options: **A** status-quo+guard-rails (050 v1) · **B** per-run extended-drain opt-in (needs a 2nd pool or the `SmartLifecycle` phase 016 rejected) · **C1/C2/C3** declaration site · **D** eligibility+MCP (`RunActionTool.MAX_WAIT_SECONDS=120` wrinkle) · **E** shutdown-aware admission + 026 cancel. Touches availability, not the gate (`GateArchTest` untouched) |
| 052 | MCP file transfer gate | ⚪ concern | **concern** (options open) — an agent can register, author and run approved actions, but cannot put a **file** on a machine; the workarounds (hand-copy, or widening a `CUSTOM` recipe into a general write primitive) either bypass review or launder an arbitrary payload through a gate that only pinned the *command*. A write is more dangerous than a pinned command because the payload is arbitrary, so the review surface must carry the **payload** and the pin must cover the **bytes** (spec-015 analogue). Axes: **A** where the gate lives (A1 notification+queue · A2 queue-only · A3 recipe-shaped — cheapest, but approving "send a file" once approves every future payload unless the blob is in `ActionSnapshot.hash`) · **B** unit of review (package-atomic vs per-file vs partial) · **C** what pins the payload (hash-at-request + stage server-side vs bytes-at-approval, a TOCTOU hole) · **D** undecided requests (expire / wait forever / reconcile like spec-016) · **E** destination safety (per-machine writable roots, sudo, overwrite, mode/ownership, path traversal) · **F** the LLM-supplied **reason**, required but unverified. Invariant across every option: **MCP gains no approve tool** — the agent proposes, only the UI decides. Carries a clickable design reference in `052-assets/` |
| 053 | App model & verb contract | ⚪ concern | **identity half → 054 (confirmed 2026-08-27, D4 in scope; verb & command contract stays here — retitle/WARNING when 054 graduates)** · **concern** (17 decisions taken 2026-08-12; 5 questions open) — carries forward **040's "what is an app"** half (040's OQ1, read-time shaping, explicitly **stays with 040**). Today an app is a **loose string** and nothing more: 026's correlation-only `app-name` + `appOps`/`opsForApp` group-by, beside a **taxonomy** (032 enums + 033 compose grouping + 038 dedication) classified server-side then re-joined client-side each poll (040's "derived twice"). Costs already biting: 050 infers the **verb from the action name** (red-team found the collision); **hand-launched/cron non-systemd native** work is undetectable (026 and 033 both find portless work — the residue is narrower than "portless"); classification emits *enums* when a probe needs *which command to propose*; MCP can only enumerate action ids. **Decided:** compose project = **one app**, flat namespace, commands may target a **part**; classification survives as a **declared label stamped once**; **closed verb vocabulary** (start·stop·restart·deploy·status·logs·metrics, MCP speaks these — F1 verb-level tools); **declared apps first-class** but narrowed to hand-launched/cron non-systemd work, **not monitored in v1**, **never auto-retire**, **machine-scoped**, and **declaring is a gated act** (proposal → UI approval, 052's invariant); **declarations split by risk** — identity + **verb** as reserved params **inside the hash**, `role`/`dedication` as un-audited **side-data** outside it (033/036 shape) — with a **new invariant** (*a declaration param is never referenced by an argv token*), since 026's own actions bind `{app-name}` into `systemctl`/`journalctl` argv, one under `sudo -n`; **C2 coexist** with 032/033/038 for v1 (end-state written down); **multi-app commands stay first-class**; **path-derived names allowed** per **028**'s `name = host` precedent (basename only, accepted at approval, raw path never on MCP); **D2** one probe constant with a detector table; 022's **normalisation + alias map** moves here. **Open:** app-name scope/collision + alias rules · declared-vs-discovered reconciliation · **where a declared app persists** (022 left entity promotion as "later work") · a **vocabulary amendment criterion** · whether **"destructive" is a declaration orthogonal to verb** (050 keys its confirm drawer to verb names, so a `migrate.sh` chip gets the *least* friction). **Impact:** closes **none** of 049/050's 15 red-team findings (0 dissolved · 2 answered · 12 untouched · **1 worsened** — the symlink-vs-resolved path); supersedes 050's verb-by-name and makes affixed script names tractable, but a **uniqueness construction still has to be written**. **Sequencing:** 049 proceeds independently; 050 drafts against the axis-A answer. Evidence in `053-assets/` |
| 070 | SSH session reuse for the discovery probe path | ⚪ todo | **the discovery connection-storm fix** (scoped to discovery — the monitor-blackout half split to **071** after a Fable-5 red-team). `MinaSshExecutor` opens a fresh connect+auth **per `exec`**, so one discovery bursts **15–30 handshakes**; a rate-limited host (`MaxStartups`) refuses a fraction → the loop's first `IOException` aborts the whole run as `ssh_failed`. **L1** a `default withSession` scope on the `SshExecutor` port (one authenticated session per discovery/facts pass, probes run as channels on it — only `MinaSshExecutor` overrides; adapters/fakes unchanged) + **L0** degrade-don't-abort (transport failure skips a family + flags `partial`; non-transport still aborts loudly; session-death handled distinctly). Blast radius incl. the `DiscoveryResult` shape, MCP `DiscoverRecipesTool`, `app.js`. Spec-003 lifecycle, not a 055–063 regression; evidence reproduced live (1 conn always ONLINE · 20-burst → 4 refused · discover ×3 → OK/​fail/​fail). Gate untouched |
| 071 | Monitor vitals sampling connection burst | ⚪ concern | **concern** (options open) — the monitor's **blank CPU/mem/disk** half, proven distinct from discovery by the 070 red-team. The monitor UI samples vitals by POSTing **runs**: `RunService` fans out one child run per `APP_PORT_LIST` item onto the `runExecutor` pool (each its own `execStreaming` connection), so a poll cycle throws **10–15 concurrent connections/machine every 5–30s** (`app.js:2052/2070`, `RunService:202`) — a **cross-operation** burst 070's per-pass `withSession` can't scope, and the likely real cause of the `MaxStartups`/fail2ban throttling that also breaks discovery. Options: **A** batched host-vitals run · **B** shared session across a poll cycle · **C** L2 per-machine pool (its "sub-minute cadence" trigger is met *here*) · **D** throttle/serialize sampling · **E** move vitals sampling off the run model to a server-side scheduler. Gate untouched. **Sequencing:** ship 070, then decide A–E on its seam |
| 054 | Lightweight app-metadata model | ⚪ concern | **concern — 5/5 forks decided 2026-08-27 (all recommended); ready to graduate into a build spec.** **Supersedes the framing of 049 + 050** and **partially supersedes 053** (identity half; confirmed now D4 landed in scope). — a clean-slate step back to a purely-mechanical model of *what an app is* and *how we size it*, deliberately independent of built/spec'd code. Unit = a triple **{app-script, script-folder, app/context-folder}**; one pipeline **Discovery → Mapping → Probing** with a Docker/common-service layer feeding all three. Discovery = ports (`ss`/`/proc` → PID → `exe`/`cwd`/`cmdline`) ∪ docker (`inspect`), cgroup-first routing so a container's overlayfs path never reads as an app-folder; blind spots = DNAT/`docker-proxy` ports + non-listening apps. Mapping = a fixed **wrapper-dir rule** (`scripts`/`bin`/`frontend`/… collapse to the parent) grouping siblings under a context. Probing = disk (`du` app-folder − mounts + volumes; double-count rule), RAM (**PSS** summed, never RSS-in-a-sum), CPU (Δ`utime+stime` rate, one-exec sample), DB (logical+physical, docker-only). Docker layer = **inspect the live engine, don't parse source** (zero new deps). Headline: model holds, **~80 % of the map is unprivileged**, every privileged probe has a labelled fallback. **Open forks (5):** symlink identity key (owns 053's *worsened* symlink-vs-resolved token) · wrapper hop depth · privilege posture · non-listening apps · DB sizing scope. 053's **verb** contract stays separate; they meet where 054's chosen identity anchors 053's declared-app params. Carries a 3-identity mock + feasibility report + decision surface in `054-assets/` |
| 009 | Cloud import (discovery provider) | ⏸ parked | fast-follow after the core |

## Build order

```
spine (serial):   001 → 002 → 011 → 003 → 004
fan-out (parallel on 004):   005 · 006 · 007 · 010
converge:         008   (MCP tool surface)
then:             012 (UI)   ·   009 (cloud, parked)
```

The spine is a hard dependency chain. The fan-out specs depend only on 004/003/011
(not each other), so they build in parallel. 008 needs 005/006/011. 012 renders the
backend, so it builds after it.

**Monitoring + app-ops (specs 021–026 + 029, graduating concern 020):**

```
021 idempotency → 022 foundations → 023 monitor-machine · 024 UI → 025 app-monitors → 026 app-ops → 029 fleet
```

021 is the hard prerequisite (else monitoring shows duplicate app cards). 022 pins
the shared model (classification, the `appName` label convention, the fan-out
`APP_PORT_LIST` run mode, run-row pruning). 023 (host vitals) and 024 (dashboard,
needs ≥1 monitor recipe) then go together; 025 (app-monitor family) and 026 (app-ops
facade, mutating, adds run-cancellation for follow-mode logs) build on 022. 029
(fleet dashboard) lands last, unifying 024's per-action cards into per-app cards
across the whole fleet (tag + app-name filters, per-app mem-% of host).

**Native app topology (specs 049 → 050 — SUPERSEDED, reframed by concern 054):**

> Both 049 and 050 were closed **done/superseded → 054** without being built. The clean-slate
> **lightweight app-metadata model** (054) re-frames the *identity + measurement* half with a
> uniform wrapper-directory rule and a generic per-context probe; the *verb / lifecycle-controls*
> half (050) is a **053** (verb contract) question. The description below is retained as the
> historical framing 054 stepped back from.

```
049 app-folder & footprint  →  050 lifecycle-script detection & controls   (→ superseded by 054)
```

049 teaches the port→PID→cmdline classifier to also resolve an app's **folder + size**
(a read-only `footprint` probe, UI-assembled) — the native counterpart to docker's
image/volume story. 050 stacks on it: from the same folder+PID it detects the app's
**lifecycle scripts** (`run.sh`/`kill.sh`/…), proposes them as gated content-pinned
`CUSTOM` actions, and renders the approved ones as start/stop/restart/deploy **controls
on the monitor cards** — the unmanaged counterpart of spec-026's systemd controls. Both
are UI-only assembly (040); neither adds a backend model or touches the gate. **Concern
051** (run execution policy) was split out of 050's long-deploy residual — 050 ships on
the bounded-drain baseline and does not block on it.

## Deferred hardening backlog

Findings raised by spec-eval during the builds, deliberately deferred (not
blocking) — candidates for future `todo` specs. The ARCH.md **S1–S8 deferred-risk
register** covers the security posture (auth/transport/host-key/sudo/cloud-creds/
rate-limiting); the items here are correctness/robustness follow-ups.

| # | Finding | From | Priority |
|---|---------|------|----------|
| H1 | `RunOutputHub` never evicts run channels (unbounded memory) and holds the per-channel lock during SSE network I/O (a slow client stalls the SSH thread) — add eviction/TTL + release the lock around I/O | 005 | high |
| ~~H2~~ | ~~Re-running discovery isn't idempotent (duplicate recipes)~~ — **promoted to spec 021** (reconcile by `(machine, type, name)`; refresh unapproved proposals in place, diff APPROVED-differs, uniqueness guard) | 006 | medium |
| H3 | `DiscoveryService.discover` runs SSH probes inside the DB transaction — probe outside the persistence tx | 006 | medium |
| H4 | `DatabaseDiscoverer` backup filename is fixed per engine (overwrites across DBs / repeated runs) — template from the `db` param | 006 | low |
| H5 | Custom-script **content-pinning**: hash the script at approval, verify before each run (path-not-contents trust; escalation risk with sudo) — **promoted to spec 015** | 007 | medium |
| H6 | `ConnectivityCheckJob` probes the whole fleet inside one `@Transactional`; `MinaSshExecutor` builds a fresh SSH client per `exec()` — move to bounded concurrency + a pooled client | 003 | medium |
| H7 | `ActionSnapshot` canonical serialization uses unescaped delimiters (theoretical hash-collision surface; currently moot) | 004 | low |
| H8 | Dead server-side helpers duplicate client logic — `MonitorDtos.opsForApp` (026) and `MonitorDtos.memPctOfHost`/`parseHostMemTotalMb` (029) are unused in production (metrics computed client-side) and only test-called; drop them or wire them so mem-% has a single source of truth (surfaced by spec-eval on PRs #38/#39; also carried as an open item in concern **031**) | 026/029 | low |

**Promoted:** **H1 + H3 + H6 → spec 013** (runtime resource hygiene) — grouped by
their shared root cause (holding a resource across network I/O); ✅ **shipped on
`main`**. **H5 → spec 015** (custom-script content-pinning, ⚪ todo) — a *security*
spec beside the ARCH S-register (posture, not robustness); resolves H5 and hardens
S5. **H2 → spec 021** (discovery idempotency, ✅ done) — the monitoring
prerequisite. **H4 / H7** remain backlog.

**Post-v1 follow-ups:** **016** (graceful shutdown + orphaned-run reconciliation,
from the runtime-lifecycle review) is ✅ **done on `main`**. The one live
content-pinning todo is **015** (the TOCTOU hole; hash-at-approval + re-hash-at-run).
Also `todo`: **027** (a *concern* — signal-driven going-OFFLINE, options still open)
and **028** (an authored *security* spec — machine name & MCP identity hardening,
ARCH S9). **017** is a *concern* (not a decision) weighing the transaction-boundary
strategy behind 013's H3/H6 — its leaning: keep the injected `TransactionTemplate`.

**Resolved (shipped in 008):** MCP actor-propagation. `ScopedValue` is
thread-confined and the MCP SDK dispatches tool handlers off the request thread, so
`CurrentUser.require()` inside a tool would throw — 008 fixed it with
`immediateExecution(true)` (tools run on the token-bound request thread) plus a test
asserting the user resolves inside a tool call.

## The docker-monitoring epic (specs 032–035)

Concern **030** graduated into a four-spec build (the *how* now lives in the specs; 030
keeps the problem framing). Dependency order — 033 and 034 parallelise once 032 lands:

```
032 axes foundations → ( 033 docker discovery  ·  034 monitor UI/UX ) → 035 discovery enablement
```

- **032** pins the shared **consumer contract** (RAM/CPU/disk as % of host; role/source/
  dedication/owner/usedBy/bucket) so 033 (backend) and 034 (frontend) can build in
  parallel; it also folds in the **H8** dead-helper cleanup.
- **033** adds docker-native discovery keyed on the `com.docker.compose.project` label
  (compose project = app), classifies datastores, and takes metrics from `docker stats` /
  `ps -s` / `system df -v`. Gate untouched (`MONITOR` recipes only).
- **034** is the UI/UX redesign — segmented tri-axis bars, the **databases lens**
  (Dedicated owner-split vs Shared used-by), hidden docker/system buckets — in the
  spec-012 idiom; design reference [`docs/fleet-resource-mock.html`](../docs/fleet-resource-mock.html).
- **035** gates *whether* a discoverer may probe: **per-family enablement, docker off by
  default** (the socket is root-equivalent). Enablement is not the approval gate.

Three follow-ups made the docker/native axes actually live (all ✅ done): **037** polls
the docker metric reads into the axes (+ the `nproc` `cores` denominator), **038** groups
a compose project into one card (datastores as `services[]`, feeding the Databases lens),
and **039** fills the native app CPU axis from the process-tree probe. Concern **040**
then steps back to ask how much of this classification should live in the backend at all.

## Open concerns

- **[036](./036-todo-recipe-param-discovery-lifecycle.md) — Recipe & param discovery lifecycle.**
  The lifecycle *after* approval: vanished-resource recipes never retire (they linger,
  including as runnable APPROVED), there is no delete/hide/suppress (revoke is the only
  stop), and fan-out lists are all-or-nothing while discoverers enumerate broadly.
  Options span retire-vs-mark-stale, an ignore/suppress list, per-item curation, and
  discoverer scoping — turning on the gate-safety asymmetry (narrowing is free; the
  hashed `app-name` ALLOWED_SET is not). Follows the 032–035 epic; grounded in code.

- **[031](./031-todo-deferred-followups-triage.md) — Deferred follow-ups triage.**
  A single worklist of every deferred note and spec-eval finding across the built
  specs and merged PRs, each re-asked **keep / drop / already-addressed**. It is a
  triage index, not a work spec: resolving it spins the *keep* items into their own
  specs (or folds them into the H-backlog above).
