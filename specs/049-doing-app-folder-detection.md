# 049 — App-folder & footprint detection

**Status:** todo · no branch yet · no Linear (blocked for this repo; tracked as
`spec-049`).

## Context

The monitor discovers a **native** (non-docker) app by walking port → PID → cmdline
(`AppMonitorDiscoverer`, spec-025) and classifies its framework — but it never learns
**where the app lives on disk** or **how big it is**. The asymmetry is visible today:

- Docker consumers get a full disk story — writable layer via `docker ps -s`, named
  volumes via `docker system df -v`, normalized to % of the data-root FS (spec-037).
- A native consumer's disk axis is hard-coded to nothing: the consumer drawer renders
  `Disk —` with the caption *"native process — no attributable disk footprint"*
  (`openConsumerDrawer` in `app.js`), and spec-039 explicitly left it that way
  ("Disk stays `—` for native (no attributable footprint)", catalog row 039).

Yet the raw material is already in hand. The classifier reads `ss -ltnp` and
`/proc/<pid>/cmdline`, and even reads `/proc/<pid>/cwd` today (its `deployDirName`
name-fallback) — it just throws the path away after extracting a label. One more probe
turns the same chain into a **folder** (`/proc/<pid>/cwd`, `readlink -f
/proc/<pid>/exe`, the `-jar` path on the cmdline) plus a **size** — feeding the app
card and the drawer's dead Disk meter, and giving spec-050 (app lifecycle scripts) a
concrete folder to anchor `run.sh`/`kill.sh` proposals to.

## Decision

Detection is a **new fixed, read-only probe action** — `footprint` — appended to every
app-monitor family (`springboot` / `fastapi` / `http` / `generic`) exactly like the
spec-032 `cpu` check: a source-controlled constant `sh -c` script whose **only** bound
param is the validated port (`$1`), fanned out over the recipe's `APP_PORT_LIST`
(S4-safe: the script string never varies per item). The script does the whole FS walk
**on the target** and emits **one NDJSON line per port**; the backend stays "run
approved script → hand back stdout" (the spec-040 thin-BE leaning — **no** persisted
classification model, no new table, no new `RecipeType`, no DTO enum). The UI parses
the line and assembles the card.

Three **distinct** numbers, never conflated:

1. **artifact** — `stat -c%s` of the jar / entry file. Small, stable, always cheap.
2. **footprint** — `du -sb <appRoot>`, **only when the root classifies as a deployed
   folder** (no build markers). On a build tree it would sum `target/` /
   `node_modules/` / `.git` — easily 10× the deploy and IO-heavy — so build trees skip
   it and say so. Always labeled *approximate*.
3. **data** — a conventional grow-dir (`data/`, `storage/`, `uploads/`, Django
   `media/`, Flask `instance/`) under the app root — the number an operator actually
   watches.

The emitted schema is **language-agnostic**; a small per-runtime detector table inside
the script fills the runtime-specific fields (java + python fully in v1; node
(`package.json`) and go (static `exe` *is* the artifact) are natural rows to add, not a
redesign).

**Gate untouched.** The `footprint` action is proposed `PENDING_APPROVAL` through the
unchanged spec-004 gate and approved once, in the UI, like every action — discovery
only proposes (spec-006 contract); nothing auto-approves and MCP cannot approve
(`GateArchTest` stays green). On an already-APPROVED app-monitor recipe, spec-021
reconciliation lands the new action as a pending addition for the human to approve.

### NDJSON schema (one object per port, emitted by the script)

```json
{"v":1,"port":8080,"pid":4242,"user":"deploy","runtime":"java",
 "cmdline":"java -jar /opt/orders/current/orders.jar",
 "cwd":"/opt/orders/releases/123","exe":"/usr/bin/java",
 "artifact":"/opt/orders/releases/123/orders.jar","artifactBytes":48234511,
 "appRoot":"/opt/orders/releases/123","link":"/opt/orders/current",
 "rootKind":"deploy","buildTool":null,
 "markers":[],
 "dataDir":"/opt/orders/releases/123/data","dataBytes":104857600,
 "footprintBytes":167772160,"footprintSkipped":null,
 "notes":[]}
```

- `runtime`: `java` | `python` | `node` | `go` | `unknown` — the detector-table key.
- `rootKind`: `build` (build markers found — footprint suppressed) | `deploy`
  (standalone artifact dir, no build markers) | `cwd-only` (heuristics failed; `appRoot`
  is just the process cwd, honesty label) | `unresolved` (not even a cwd — e.g.
  `python -c`, permissions denied).
- `buildTool`: `maven` | `gradle` | `poetry` | `pip` | `pipenv` | `django` | `null`.
- `markers`: the marker files that confirmed the root (`pom.xml`, `pyproject.toml`, …).
- `footprintSkipped`: `null` | `"build-tree"` | `"timeout"` | `"permission"` — why
  `footprintBytes` is absent, so the UI can say it instead of guessing.
- `link`: the **un-resolved stable path** when the artifact/root was reached through a
  symlink (e.g. `/opt/app/current`), `null` otherwise. `appRoot` is always the
  `readlink -f`-resolved real dir; `link` is what the UI shows as identity (decision:
  symlink identity is stable across redeploys, the resolved target is secondary).
- Absent/undeterminable ⇒ the field is `null`, never a fabricated value (the 032 §1
  "absent is null" rule). All paths are JSON-escaped by the script (`\` and `"`).

## Heuristics

### Resolving the process (all runtimes)

- port → PID(s): `ss -ltnpH` — the same probe shape as `PROCESS_PROBE_SCRIPT`.
- Several PIDs own one port (gunicorn/uvicorn workers inherit the master's socket):
  pick the PID that is the **parent of others in the set** (the master), else the
  lowest PID. The master's cmdline/cwd are authoritative; workers merely inherit.
- PID → cwd: `readlink -f /proc/<pid>/cwd`; PID → exe: `readlink -f /proc/<pid>/exe`.
- **Key insight:** for a JVM, `exe` is just `/usr/bin/java` — the artifact comes from
  parsing the cmdline (`-jar <path>`), never from `exe`. The same trap exists for
  python: a venv's `bin/python` is a *symlink* to the system interpreter, so
  `readlink -f exe` resolves to `/usr/bin/python3.x` and loses the venv — use
  `argv[0]` from cmdline and `VIRTUAL_ENV` from environ instead (below).
- Walk-up bound: at most **5** parent levels from the starting dir, stopping at `/`
  or the login user's `$HOME` — never an unbounded crawl.

### Java

Artifact = the token **immediately after `-jar`** (not a `-javaagent:`/`-cp` jar —
the existing `jarPathAfterDashJar` precedent). Classification and `du` use the
**`readlink -f`-resolved** real dir (`/opt/app/current → releases/123`), but the
**displayed identity keeps the symlink** — see the symlink decision below. Then
classify `dirname(resolved artifact)` by walking up:

| Observation | Conclusion |
|---|---|
| jar sits in `target/` **and** `../pom.xml` exists | `buildTool=maven`, `appRoot=..`, `rootKind=build` |
| jar sits in `build/libs/` **and** `../../build.gradle{,.kts}` or `settings.gradle{,.kts}` exists | `buildTool=gradle`, `appRoot=../..`, `rootKind=build` |
| jar's dir has **no** build markers (`pom.xml`, `build.gradle*`, `settings.gradle*`, `src/`) | that dir **is** the app folder — a deployed release, `rootKind=deploy` |
| no `-jar` (started `-cp … com.example.Main`, exploded classpath) | no single artifact; `appRoot = cwd`, `rootKind=cwd-only` (decision: **no** first-classpath-entry guess — too many false positives; show the cwd honestly) |

`artifactBytes = stat -c%s <jar>`. A `deploy` root gets the `du` footprint and the
data-dir scan (including the H2-style `*.mv.db` / `*.db` glob one level deep as a
`dataDir` hint); a `build` root gets `footprintSkipped="build-tree"` — folder +
artifact size only.

### Python

No single artifact exists — the detector triangulates from **cmdline shape**,
**interpreter path**, and **cwd + markers**, in that order:

**1. Cmdline shapes** (first match wins; `argv0` is the first cmdline token):

| Shape | Entry / root derivation |
|---|---|
| `python app.py` / `python src/main.py` | entry = the `.py` arg resolved against cwd; marker-walk up from `dirname(entry)` |
| `python manage.py runserver` | `manage.py`'s dir **is** the Django project root (`buildTool=django`) — the strongest python signal |
| `gunicorn myproj.wsgi:application` / `uvicorn app.main:app` (also `python -m uvicorn …`) | the `module:attr` token (first non-flag arg containing `:`) maps to a file: `app.main` → `<cwd>/app/main.py`; entry = that file if it exists, root from marker-walk of cwd |
| `flask run` | entry from `FLASK_APP` in `/proc/<pid>/environ` (perms permitting), else cwd-only |
| `celery -A proj worker` | usually portless (out of the port-driven chain); if it does own a port, treat `proj` like a module token |
| `python -c …`, bare REPL, script outside any marked tree | **unresolved** — emit `rootKind=cwd-only` (or `unresolved`), show cwd, claim nothing more |

**2. Virtualenv detection** (independent signal, also names the project):
- `argv0` matching `*/bin/python*` where `dirname(dirname(argv0))/pyvenv.cfg` exists ⇒
  that dir is the **venv root**; when it is named `.venv`/`venv`/`env`, the project is
  its **parent** (`/srv/app/.venv/bin/python` ⇒ project `/srv/app`).
- `VIRTUAL_ENV=` from `tr '\0' '\n' < /proc/<pid>/environ` — same conclusion, but
  `environ` is readable only same-user/root (stricter than `cwd`), so it is a bonus
  signal, never the only path.
- The venv is reported as the venv it is: its size lands in `footprintBytes` when the
  root is `deploy`-like, and a note (`"venv=<path>"`) rides in `notes` — a python
  "artifact" (the entry `.py`, a few KB) is honest but near-meaningless, and the spec
  says so rather than inflating it.

**3. Marker confirmation** — walk up from the entry dir (or cwd) until a marker hits:
`pyproject.toml`, `setup.py`/`setup.cfg`, `requirements.txt`, `Pipfile`,
`poetry.lock`, `manage.py`, `wsgi.py`/`asgi.py`, a `.venv`/`venv` dir. First hit =
`appRoot`, markers recorded.

**Deploy floor (decision): marker OR venv resolves ⇒ `rootKind=deploy`** — a python
root is treated as deployed (so `du` runs and it feeds the disk axis) when **either** a
project marker hits **or** a venv-parent resolves (signal 2). Only a bare `cwd` with
**neither** a marker nor a venv stays `rootKind=cwd-only` (folder shown, no `du`, no
axis segment). This is the more inclusive of the two floors — most real services carry
a marker or run from a venv, so they get sizes; the small risk (a `du` on a
slightly-too-broad resolved dir) is bounded by the `timeout 10 du` budget above.

**Where python stays genuinely ambiguous** — a system python running `-c`, a cron-ish
script with no markers, an environ we cannot read — the line says `unresolved` /
`cwd-only` and the UI shows the cwd with an "unresolved" tag. No guessing.

### Detector table (language-agnostic seam)

The script is one fixed body with a `case`-style detector table keyed on
`runtime`-classification of `argv0`/`exe`/cmdline: `*java*` → java row, `*python*` /
`gunicorn` / `uvicorn` / `celery` → python row, `*node*` → node row (nearest
`package.json` up from the entry script — stub in v1: runtime + exe only), a static
binary whose `exe` is not an interpreter → go/binary row (the `exe` **is** the
artifact; `stat` it, marker-walk for `go.mod` only labels a build tree). Adding a
runtime = adding a table row, not touching the schema or the UI contract.

### `du` gating

`footprintBytes` runs only for `rootKind=deploy` (and the data-dir scan), as
`timeout 10 du -sb --one-file-system <dir> 2>/dev/null`; a timeout emits
`footprintSkipped="timeout"`. Build trees never get `du` (`"build-tree"`); unreadable
dirs emit `"permission"`. The UI labels every footprint **approximate**.

**Sampled, not per-poll (decision).** `du` is IO-heavy, so the `footprint` probe is
**not** run at the 5 s poll tier. The UI polls it only on the **slowest active tier**
(1 m / 5 m) — and it may run once at discovery for an initial value — so folder + sizes
lag the fast metrics rather than hammering disk every 5 s. The cheap parts of the line
(port/pid/cwd/appRoot/artifact `stat`) are all that the fast tiers would want anyway;
they ride the same probe, so the whole `footprint` line simply refreshes on the slow
cadence. (The lighter alternative — an operator opt-in flag per recipe — is recorded
but not chosen; sampling keeps it zero-config.)

## Implementation sketch

- **`AppMonitorDiscoverer`** (the seam this spec extends — same file, same idiom):
  a new `FOOTPRINT_PROBE_SCRIPT` constant beside `PROCESS_PROBE_SCRIPT` /
  `CPU_PROBE_SCRIPT`, and a `footprintProbe()` action (name `footprint`,
  description "App folder, artifact/data/footprint sizes from /proc + fs markers.
  Read-only.") appended to every family's action list in `recipeFor(…)`. Fixed
  `sh -c` template, port as `$1`, `APP_PORT_LIST` fan-out — byte-for-byte the shape
  the existing probes use, so `RunService`, the gate, and spec-021 reconciliation all
  apply unchanged. POSIX-shell only (it runs under `sh`), JSON-escaping helper
  included in the constant.
- **No backend model change.** No Flyway migration, no new DTO fields, `monitor/`
  untouched: the probe's stdout flows back through the existing run-output path the
  other checks use.
- **`app.js`** (assembly lives here, spec-040 leaning):
  - `checkKind()` learns `footprint`.
  - `applyConsumerReading(…)`: the `kind === "footprint"` branch JSON-parses the
    port's line into `c.folder`, `c.rootKind`, `c.buildTool`, and
    `c.sizes = {artifactBytes, dataBytes, footprintBytes, footprintSkipped}` —
    malformed/absent line ⇒ all stay `null` (honest `—`), the check still rolls up
    to its up/down chip.
  - **Card**: a mono, middle-truncated folder line under the app name plus one size
    chip (footprint when present, else data, else artifact — labeled which). When
    `link` is set, the folder line shows the **symlink** path as identity
    (`/opt/app/current`) with the resolved target (`→ releases/123`) as a second
    line / `title` tooltip — stable across redeploys, which is what spec-050 keys its
    lifecycle scripts to.
  - **Drawer** (`openConsumerDrawer`): the native `diskMeter`'s *"no attributable
    disk footprint"* caption is replaced, when a footprint line exists, by a
    `Folder` KV block — path (+ `rootKind`/`buildTool` tag) and the three sizes as
    separate labeled rows, `footprintSkipped` rendered as its reason ("build tree —
    footprint suppressed", "timed out", "permission denied"). No line ⇒ today's
    caption stays.
  - The native **disk % axis is fed** from `footprintBytes` (decision below): the
    deployed app's footprint becomes an attributed disk segment on the machine bar —
    **and** the card keeps its size line/chip; both surfaces show, they are not
    exclusive. This is a **joint 049 + 041 change**: 041's
    `OTHER = host_used − Σ attributed` disk computation (`computeOther` in `app.js`)
    must now subtract the newly-attributed native disk bytes as well, or the deployed
    app double-counts (its footprint would appear both as its own segment and inside
    OTHER). Only `rootKind=deploy` apps with a real `footprintBytes` feed the axis;
    build-tree / `cwd-only` / absent-footprint apps contribute nothing to the axis
    (their disk stays `—`, unchanged) and 041's OTHER is untouched for them.
  - `textContent`-only via `h()` (the 012 invariant); a headless render-check in
    `src/test/js/` drives a parsed line onto card + drawer and asserts the
    `unresolved`/skipped degradations.
- **Tests**: `AppMonitorDiscovererTest` gains assertions that every family proposes
  the `footprint` action, that the script is a fixed literal with the port as sole
  param, and that probes stay read-only; the NDJSON parsing lives in the JS
  render-check. Gate + `mcp/` + `*ArchTest`s untouched.

## Permissions & coverage

Coverage is a **direct function of the machine's `loginUser`** — state it on the card,
not in a footnote:

- `ss -ltnp` reveals the owning PID only for the login user's **own** sockets unless
  root/`CAP_NET_ADMIN` — the standing S5 posture from spec-025: other users' apps are
  invisible, by design (no `sudo` in probes).
- `/proc/<pid>/cwd` and `/proc/<pid>/exe` readlink only same-user or root;
  `/proc/<pid>/environ` is stricter still. A PID we can see but not read emits
  `rootKind=unresolved` with `footprintSkipped="permission"`.
- `du`/`stat` need ordinary read permission on the app folder; a root-owned deploy
  dir under a non-root login degrades to folder-without-sizes.

So: **login-as-the-app-user (or root) ⇒ full folder + sizes; anything else degrades
honestly** — the feature promises no more than the login user can see.

## Resolved design questions (review 2026-07-23)

All five open questions were decided with the user and folded into the body above:

1. **Disk % axis — FED, and the card size line stays too** (both surfaces, not
   exclusive). Deployed native apps get a real attributed disk segment; the card keeps
   its size chip. **Joint 049 + 041 change**: 041's `computeOther` must subtract the
   newly-attributed native disk bytes from `OTHER = host_used − Σ attributed`. Only
   `rootKind=deploy` apps with a real `footprintBytes` feed the axis.
2. **`du` — sampled, not per-poll.** The `footprint` probe runs only on the slowest
   active poll tier (1 m / 5 m) + optionally once at discovery, never at 5 s. (Opt-in
   flag considered, not chosen — sampling stays zero-config.)
3. **Python deploy floor — marker OR venv resolves** ⇒ `rootKind=deploy` (unlock
   `du` + axis). Only a bare `cwd` with neither stays `cwd-only`.
4. **Symlinked releases — show the symlink as identity**, resolved target as a second
   line / tooltip (`link` field). Stable across redeploys; what spec-050 keys to.
5. **`-cp` / exploded-classpath java — no guess**, straight to `cwd-only` (the
   first-classpath-entry fallback was dropped as too false-positive-prone).

No residual open questions for 049. (The graceful-shutdown-vs-long-run question lives
in spec-050; the 041 disk-math edit is tracked in Related / Known Gaps below.)

## Known Gaps

- **One-shot, best-effort sizes.** `du` is a point-in-time scan with a 10 s budget;
  a huge deploy tree reads as "timed out", not a number. No time-series (the standing
  v1 monitoring posture).
- **Footprint ≠ accounting.** `--one-file-system` `du` misses bind-mounted data,
  counts hard links once, and races live writes — *approximate* is part of the label,
  not a disclaimer to remove later.
- **Node/go are stubs in v1.** The detector table has their rows sketched
  (`package.json` walk; static-binary artifact) but only java + python emit full
  lines.
- **Other-user apps stay invisible** (S5) — unchanged from spec-025; this spec widens
  what we learn about visible apps, not which apps are visible.
- **Folder paths are UI-only.** `appRoot`/`artifact` are infra topology; they ride
  the UI-only monitor read (which already carries `host`/`loginUser`) and must not be
  added to any MCP view — the S9 line (spec-028) extends to filesystem paths.

## Related

- **spec-040** (monitor runtime view & model weight) — the thin-BE leaning this spec
  is built to: BE runs approved scripts and returns stdout; classification/assembly
  stays in `app.js`; no persisted model.
- **spec-025** (app-monitor recipes) — the `ss → PID → /proc` classifier and the
  fixed-`sh -c`-script probe idiom this extends; **spec-032** (`cpu` check — the
  add-an-action precedent), **spec-037/039** (the disk/CPU axes; 039 pinned native
  disk at `—` — the gap this fills), **spec-041** (OTHER segment — this spec **amends**
  its `computeOther` disk math to subtract the native disk bytes 049 now attributes,
  so a deployed app is not double-counted; see the disk-axis decision).
- **spec-050** (app lifecycle scripts) stacks on this: the detected `appRoot` is
  where its proposed `run.sh`/`kill.sh` live.
- **ARCH.md**: the gate enforcement points (approval UI-only; discovery proposes,
  never approves), **S2** (server-held key — probes run over the server's SSH, so
  detection is server-executed even though assembly is client-side), **S4** (fixed
  script template, validated port as the sole positional param, never interpolated),
  **S5** (login-user coverage), **S9** (paths stay off the MCP surface).

## Acceptance

Register a native **java** app (one running from a `target/` build tree, one from a
deployed `/opt/<app>/` dir with a `data/` subdir) and a native **python** app
(uvicorn/gunicorn under a `.venv`, started from its project dir). Run discovery,
approve the new `footprint` action on each app-monitor recipe in the UI. The monitor
card for each app shows its **folder** and **a size**: the deployed java app shows
artifact + data + approximate footprint; the build-tree java app shows folder +
artifact with "build tree — footprint suppressed"; the python app shows its project
folder (venv-parent or marker hit) + sizes; a `python -c` straggler shows cwd tagged
"unresolved". Nothing runs without UI approval; `GateArchTest` and the full suite
stay green.
