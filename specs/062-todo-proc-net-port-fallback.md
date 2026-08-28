# 062 — Native /proc/net/tcp port fallback + fd-inode PID join

**Status:** todo · Linear [BOL-891](https://linear.app/iskeru/issue/BOL-891) · build branch `moacyrricardo/bol-891-cpt-062-proc-net-port-fallback`. **Blocked by 056 (BOL-885).**

## Context

Spec [056](056-done-app-discovery.md) shipped the unioned app-map sweeps but deferred its
Implementation Notes item 2 and the "minor" half of item 3 (056:288–291): the **`/proc/net/tcp{,6}`
fallback + fd-inode PID join**, the **`exe`-as-app-script signal**, **nginx real-root via
`nginx -T`**, and **relative interpreter-script resolution**. This spec graduates exactly that
slice — the native robustness path of the listening sweep. The Docker-branch enrichment (056
deferral item 1) is **not** here.

The gaps, in today's code:

- **A denied `ss` join silently drops the listener.** `AppMonitorDiscoverer.listeners`
  (`discovery/service/AppMonitorDiscoverer.java:294`) runs `ss -ltnp` and hands the output to
  `parseSs` (:355), which **requires** `users:((` on every line (:358) — but unprivileged `ss`
  prints the process column only for the login user's own sockets. A LISTEN socket owned by
  another user renders with a *blank* users column and is dropped, exactly the "never read a blank
  users-column as 'no process'" failure 056 Decision 1 forbids. The `netstat -ltnp` fallback
  (:299) has the same property, and a box with neither tool yields an empty port inventory.
- **`exe` is unread.** Context resolution keys solely on `/proc/<pid>/cwd`
  (`resolveContext` :522 → `cwdPath` :532 / `realCwdPath` :542). A compiled binary launched with
  `WorkingDirectory` unset (systemd default: `/`) maps its context from `/` and clamps at a
  boundary root — while `readlink /proc/<pid>/exe` would have named the deploy folder directly.
  055's build notes skipped `exe` because for *interpreters* it resolves to `/usr/bin/python3`;
  for a compiled app the trade-off inverts.
- **nginx's context is a catalog guess.** The fingerprint verify step (`verifiedDataDir` :711)
  has no env override for nginx (`ServiceCatalog.java:42`, `dataDirEnvVar = null`), so its
  context is always the Debian default `/var/www` even when every vhost roots elsewhere.
- **Relative interpreter scripts are rejected.** `interpreterScript` (:652) accepts the first
  non-flag token only when it is absolute or matches a known extension (:662) — `python3 run`
  or `node server` started from the app folder is invisible to the 056 interpreter sweep.

All work lands inside the existing `APP` discoverer (`DiscovererFamily.APP`,
`AppMonitorDiscoverer.java:74`) behind the unchanged `RecipeDiscoverer` port — no gate change,
no MCP-surface change (ARCH.md gate points 1–5, S4/S9 rows).

## Decision

1. **The port inventory never depends on the `ss` process join.** `parseSs` keeps every LISTEN
   line: a line with `users:((` yields an attributed `Listener` as today; a line without one
   yields an **unattributed** `Listener` (null pid, null process) instead of being dropped.
   Whenever any unattributed listener exists after the `ss`/`netstat` parse — or neither tool
   produced output — the sweep runs **one constant `sh -c` fallback script** that (a) reads
   `/proc/net/tcp` + `/proc/net/tcp6`, filters `st == 0A` (LISTEN), and prints each socket's
   hex `local_address` and **inode**, and (b) scans `/proc/<pid>/fd` symlinks for
   `socket:[<inode>]` targets. Java decodes the hex port and joins inode → PID. The merge fills
   PIDs where the join succeeds, adds ports `ss` never saw (no-`ss` hosts), and **de-duplicates by
   `(local_address, port)`** — a distinct `127.0.0.1:8080` and `0.0.0.0:8080` both survive (a
   port-only key would drop one). An **`ss`-attributed** listener always wins over a fallback
   attribution for the same `(addr, port)`; a shared listening inode held by several preforked PIDs
   resolves to the **lowest PID** (the master), which drives context derivation. A port whose join
   still fails is emitted as a degraded record — null PID, family GENERIC, appName `app-<port>`,
   `runtime = process`, `confidence = low`, path-free `sourceNote` — **but only if no other channel
   already owns that `(addr, port)`**: an unattributed port is **suppressed** when a
   listening/systemd/fingerprint record (this sweep or 056's non-listening sweeps) already claims
   it, so a root-owned service the systemd sweep found as a unit is never doubled as an `app-<port>`
   card and daemon noise (`sshd`, `resolved`, CUPS…) never floods the map. The residual — a
   genuinely unclaimed port, typically a loopback-only daemon no channel identified — stays one
   low-confidence record (see Known Gaps + the 059 note). This is the 056 D1 / 054-D3
   degrade-and-label posture, reconciled across channels. The probe stays S4-safe:
   the script body is a source-controlled constant with **zero bound inputs**, run no-sudo.
2. **Honest reach — a port-recovery path, run every sweep.** Unprivileged, `/proc/<pid>/fd` of
   another user's process is unreadable — the same kernel check that blanks `ss`'s column. So the
   join recovers **PIDs** only in a narrow population: a port-listing tool that showed **no**
   process column at all (e.g. **busybox `netstat`**, or an `ss` without `-p` support) — a normal
   `ss -ltnp` already attributes the login user's own sockets, and a *foreign* socket's fd dir is
   unreadable to the fallback for the very same reason its `ss` column was blank. On a normal host
   the fallback is therefore not primarily a PID-recovery path but a **port-recovery** path: it
   guarantees an unattributed listener is emitted as a *port* (null PID, `confidence = low`), never
   dropped. **Cadence & cost (accepted):** because virtually every host runs at least one
   root-owned listener (sshd), an unattributed listener almost always exists, so the fallback probe
   runs on **essentially every discovery** — two extra forks per sweep, cheap beside the per-PID
   `/proc` reads already done, and Decision 1's cross-channel reconciliation keeps its output from
   becoming noise. The sudo re-probe that would attribute foreign sockets is 054 D3 / 057 scope,
   not here.
3. **`exe` is a fallback context signal, not a new primary.** For a `PROCESS`/`SYSTEMD` PID whose
   `cwd` **resolves to a boundary root** (`/` or a member of `ContextMapper.boundaryRoots()` — the
   systemd `WorkingDirectory`-unset case), read `readlink /proc/<pid>/exe`. The *cwd-unreadable*
   case is deliberately **not** a trigger: `/proc/<pid>/cwd` and `/proc/<pid>/exe` sit behind the
   **same** ptrace-mode access check, so a PID with an unreadable cwd has an equally unreadable
   exe — that arm would be dead; only the boundary-root arm can fire. The link target is
   **normalised** first: a trailing `" (deleted)"` suffix (a binary replaced since start — exactly
   the redeploy case this targets) is stripped and the real path used, and any target still not
   absolute is rejected (the PID falls back to cwd/none). When the normalised exe lives **outside**
   the fixed packaged-binary prefixes `{/usr, /bin, /sbin, /lib, /lib64, /snap}`
   (`PACKAGED_BINARY_ROOTS`), resolve the context from the exe's parent directory through the
   existing `ContextMapper` seam (the wrapper rule collapses `/opt/app/bin/server` → `/opt/app`).
   cwd stays primary (055's decision stands); appName derivation is untouched (D5 basename seed).
4. **nginx real root via `nginx -T`.** The Decision-5 verify step for the nginx fingerprint runs
   fixed-argv `nginx -T` (read-only config dump), parses `root <path>;` directives, and takes the
   most frequent root as the verified data dir fed to context resolution. Denied or absent
   (unprivileged `nginx -T` commonly fails opening logs/certs) → the catalog default stands,
   the same degrade rule `verifiedDataDir` already applies to `/proc/<pid>/environ`.
5. **Relative interpreter scripts resolve against the process cwd.** The interpreter sweep's
   candidate token, when not absolute, is anchored as `<cwd>/<token>`, charset-validated
   (`[A-Za-z0-9._/-]+`, the :776 precedent), and accepted iff a fixed-argv existence probe
   confirms the file. `python3 -m uvicorn`-style module tokens keep failing (no such file in
   cwd). Unreadable cwd → today's absolute-or-extension rule stands unchanged.
6. **Same record, same seams, nothing new on MCP.** Every branch emits the existing nine-field
   `AppPortItem` (`discovery/AppPortItem.java:51`) with `sourceNote`/`confidence` set — no new
   field, no migration (`app_port_list` is already CLOB, V15). Paths resolved here land only in
   the S9-secret `scriptFolder`/`contextKey` side-data already guarded by `McpPathLeakArchTest`;
   every new `sourceNote` string is path-free. `discover_recipes` still only proposes
   `PENDING_APPROVAL` records; no tool approves (GateArchTest untouched).

## Implementation

All in `discovery/service/AppMonitorDiscoverer.java` (plus `ServiceCatalog`), spec-062 Javadoc
cites per CONTRIBUTING §6. Probes go through `Probes.lines` (`discovery/service/Probes.java:36`) —
fixed argv or the constant-`sh -c` idiom `PROCESS_PROBE_SCRIPT`/`CRON_PROBE_SCRIPT` already use
(:116, :182).

### The fallback probe + join

New source-controlled constant (the only `sh -c` this spec adds; no bound input):

```java
private static final String PORT_FALLBACK_SCRIPT = String.join("\n",
        "awk '$4==\"0A\" {print \"L\", $2, $10}' /proc/net/tcp /proc/net/tcp6 2>/dev/null",
        "ls -l /proc/[0-9]*/fd 2>/dev/null | awk '/^\\/proc\\// {sub(/:$/,\"\"); split($0,a,\"/\"); pid=a[3]}"
                + " /socket:\\[/ {print \"F\", pid, $NF}'");
```

- `L <hexAddr:hexPort> <inode>` lines: decode the port as `Integer.parseInt(hex, 16)` from the
  token after the last `:` of field 2 (works for tcp and tcp6 rows; the header row's `$4` is
  `st`, never `0A`, so no header guard is needed).
- `F <pid> socket:[<inode>]` lines: one `ls -l` invocation over all fd dirs (the whole fallback is
  a single `sh -c` — an `awk` over the two `/proc/net` files, then `ls` piped to a second `awk`: a
  handful of forks, all on the target); unreadable dirs vanish into `2>/dev/null` — precisely the
  foreign-PID case.
- Join in Java: `Map<inode, port>` × `Map<inode, pid>` → attributed listeners; leftover L-rows →
  unattributed listeners.

Changes around it:

- `Listener` (:936) allows null `pid`/`process`; Javadoc states the degraded meaning.
- `parseSs` (:355): drop the `users:((` requirement; a LISTEN line without it emits
  `new Listener(port, null, null)`. `parseNetstat` (:370) gains the same unattributed branch for
  lines without a `pid/prog` column.
- `listeners` (:294) becomes the three-tier merge of Decision 1; the fallback runs at most once
  per discovery.
- The classify loop (:201–244) short-circuits a null-PID listener before any `/proc/<pid>` read:
  no `cmdline`/`runtimeOf`/`resolveContext` call, family `GENERIC`,
  `appName = sanitize(null, port)` (→ `app-<port>`), `runtime = process`, `confidence = "low"`,
  `sourceNote = "unattributed listener · discovered via port :<port> · owner unreadable"` — **but
  first reconciled (Decision 1): the record is dropped when some other channel (an attributed
  listener this sweep, or a 056 systemd/interpreter/cron record) already owns the same
  `(addr, port)`**. The union's final emit therefore keys on `(addr, port)`, an `ss`-attributed
  record beating an unattributed one, so only genuinely-unclaimed ports surface an `app-<port>`
  card. The `isDockerProxy` skip (:206) only applies to attributed listeners; an unattributed port
  that is really a docker-proxy publish stays a low-confidence record until the Docker-branch spec
  (056 deferral item 1) supplies published-port truth.

### The `exe` signal

- New `exePath(ssh, target, pid)`: fixed argv `readlink /proc/<pid>/exe` (validated integer PID,
  the :532 pattern). Its result is **normalised** — strip a trailing `" (deleted)"`, then require
  an absolute path or return null.
- `resolveContext` (:522): when `cwdPath` resolves to `/` or a member of
  `ContextMapper.boundaryRoots()` (`ContextMapper.java:53`-adjacent constant sets) — **not** the
  unreadable-cwd case (R1: the same ptrace check ⇒ exe equally unreadable) — and the normalised
  `exePath` is non-null and not under `{/usr, /bin, /sbin, /lib, /lib64, /snap}` (new fixed set
  `PACKAGED_BINARY_ROOTS`), resolve via the existing
  `resolveContextForDir(ssh, target, parentDir(exe))` (:736) — logical + `readlink -f` physical +
  lazy marker predicate all reused, zero new mapper logic.

### nginx real root

- `ServiceCatalog.Service` (`ServiceCatalog.java:36`) is untouched; the nginx-specific verify
  lives beside `verifiedDataDir` (:711): when the fingerprinted row is nginx and
  `Probes.commandExists(ssh, target, "nginx")`, run fixed argv `List.of("nginx", "-T")`, collect
  every `root <path>;` directive (regex `^\s*root\s+"?([^;"\s]+)"?\s*;` — surrounding quotes
  stripped), **discard** any capture containing `$` (a variable root like `root $app_root;` is not
  a literal path) and any stock default-server root (`/usr/share/nginx/html`, `/var/www/html`), and
  return the **modal** root of what remains; empty/denied/all-filtered output → `service.dataDir()`
  as today.

### Relative interpreter-script resolution

- `interpreterScript` (:652) returns the first non-flag token unconditionally; acceptance moves
  to the caller (`interpreterApps` :620): absolute → accept; else read the PID's cwd (`cwdPath`),
  build `<cwd>/<token>`, validate against `[A-Za-z0-9._/-]+`, and accept iff a fixed-argv **file**
  test passes — `List.of("ls", "-ld", candidate)` returns a single line whose mode column does not
  begin with `d` (a directory hit like `python3 somedir` is **rejected**, not taken as an
  app-script; the :745 `readlink -f` precedent covers passing a discovered path as one argv
  element). Unreadable cwd → apply the old
  absolute-or-`SCRIPT_ARG` rule (:662) so behaviour never regresses. The resolved absolute path
  feeds `baseName` for the appName (:639) exactly as before.

### Tests, migration

- No Flyway migration: no schema or record-field change (V15 already widened `app_port_list`).
- Parse helpers (hex decode, L/F join, root-directive modal pick, relative resolution) are pure —
  plain JUnit in `discovery/AppMonitorDiscovererTest` (module-level package per CONTRIBUTING §6);
  end-to-end sweeps via the existing `FakeSshExecutor` keyed on `PORT_FALLBACK_SCRIPT` and the
  new fixed argvs, including: blank-users `ss` line ⇒ low-confidence record (not dropped),
  no-`ss` host ⇒ inventory from the fallback, foreign socket ⇒ null-PID port survives,
  **`(addr,port)` reconciliation** (a systemd/fingerprint record for `:80` suppresses the `app-80`
  unattributed card; `127.0.0.1:8080` and `0.0.0.0:8080` both survive; an `ss`-attributed record
  beats a fallback one; a shared inode across preforked PIDs picks the lowest), **exe
  normalisation** (a `… (deleted)` target is stripped then resolved; a non-absolute target is
  rejected; the unreadable-cwd arm never triggers), **nginx `-T`** (quoted root unquoted; a
  `$`-variable and a stock `/usr/share/nginx/html` root filtered; modal root chosen), and
  **directory rejection** (`python3 somedir` is not accepted as a script).

## Known Gaps

- **Foreign-socket PID attribution stays unresolved** — the fd join cannot cross the user
  boundary without privilege; the on-demand sudo re-probe is 054 D3, owned by 057. This spec
  only guarantees the *port* is never silently lost.
- **Unclaimed loopback daemons stay a low-confidence card.** After Decision 1's cross-channel
  reconciliation, a port no channel could identify (typically a loopback-only daemon) still surfaces
  as a single `app-<port>` `confidence=low` record — never doubled, but not resolved to a name
  either. **059 note:** the UI should render these de-emphasised (a low-confidence tail), not as
  peers of identified apps.
- **A venv interpreter resolves to `…/venv`.** For `/opt/app/venv/bin/python` (exe outside the
  packaged roots), the wrapper rule collapses `…/venv/bin` → `…/venv`, one hop short of the app
  root — an accepted degrade; cwd (when usable) still yields the true app folder.
- **TCP only.** `/proc/net/udp{,6}` and unix sockets are out, mirroring today's `ss -ltnp`
  scope; a UDP service is still invisible to the listening sweep.
- **Docker published ports remain the Docker branch's** (056 deferral item 1, a separate spec):
  an unattributed port that is a DNAT publish is emitted low-confidence here and reconciled only
  once `docker inspect` enrichment lands.
- **nginx roots collapse to the modal directive** — a multi-vhost box maps one context; per-vhost
  contexts (and non-Debian layouts, mongo) stay deferred with the 056 catalog gaps.
- **`exe` never overrides a usable cwd** — a compiled app started *in* an unrelated writable dir
  (not a boundary root) still maps from cwd; widening the heuristic waits for field evidence.
- **Interpreter PATH resolution** (`node server` where `server` resolves via `$PATH`, `python -c`
  inline code) is out; only cwd-relative file arguments are recovered.
