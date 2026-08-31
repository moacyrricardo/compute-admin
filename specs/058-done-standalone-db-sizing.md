# 058 — Standalone database sizing

**Status:** done · Linear [BOL-887](https://linear.app/iskeru/issue/BOL-887) · build branch `moacyrricardo/bol-887-cpt-058-standalone-database-sizing`. **Blocked by 057 (BOL-886)** — satisfied (built on the 057+061+062 integration branch).

## Context

Concern [054](054-concern-lightweight-app-model.md) locked five decisions (2026-08-27). D5
put dockerized-DB sizing in scope now — logical (`docker exec … pg_database_size()`) plus
physical (data-volume `du`) — and **deferred standalone (non-docker) DB sizing to its own
spec** ("same SQL, different transport later"; 054:116-119, report `054-assets/lightweight-app-model-report.html:448`).
This is that spec. It sizes non-docker **postgres / mysql / mariadb** by running the **same
logical size queries** as the dockerized path (spec [057](057-done-app-footprint-probing.md)) but over
a **host-side transport** — peer/ident auth for postgres (`sudo -u postgres psql`) and config-file
credentials for mysql/mariadb (`~/.my.cnf`, or the root-readable `debian-sys-maint` account in
`/etc/mysql/debian.cnf`) — plus a **physical data-directory `du`**. It reconciles with the
existing `DatabaseDiscoverer` (`discovery/service/DatabaseDiscoverer.java`), which already detects
each engine (`command -v`, `systemctl is-active`) and lists databases with the exact idioms this
spec's size queries extend (`mysql -N -B -e "SHOW DATABASES"` :62-63; `psql -tAc "… pg_database
WHERE datistemplate = false"` :72-74), and with `Probes` (`discovery/service/Probes.java`) for the
fixed-argv read primitives.

058 is the **lowest-priority leaf** of the 055–060 epic and can build **after** 057. It
**depends on** 055 (the `{app-script, script-folder, context}` record and `ContextMapper` — a
standalone DB is its own context per 054/055; the S9 path-sanitisation arch test), 056 (the
Discovery sweeps, the dockerized-vs-standalone cgroup routing that hands 058 only the
*standalone* branch, and the **common-service default-folder catalog** — 056's `ServiceCatalog`),
and 057 (the per-context **DB metric pair** `{logicalBytes, physicalBytes}` record shape, the
**three cadence tiers**, the on-demand **re-probe-with-sudo** action (054 D3), and the spec-041
`computeOther` single-denominator integration). It **feeds** 059 (which displays the standalone DB
context's logical/physical pair)
and 060 (the end-to-end MCP gate/S9 audit). The approval gate (spec-004/015) is **not** touched;
every probe here is read-only and rides the existing MONITOR-check idiom.

## Decision

The following restate 054 D5's deferred standalone half prescriptively (do not reopen):

1. **Standalone DBs are sized, engine-natively, host-side.** A postgres / mysql / mariadb engine
   running as a systemd service (not in a container — the dockerized branch is 057) is sized on
   two axes: **logical** (an engine SQL aggregate) and **physical** (a `du` on the engine's data
   directory). Both numbers are reported; they legitimately disagree (physical ≥ logical; the gap
   is WAL/binlog/bloat — report `…:445`).

2. **The logical query is identical to 057's dockerized path — only the transport differs.**
   057 owns the SQL; 058 reuses it verbatim over a host-side transport instead of `docker exec`.
   Per engine (all **param-free**, whole-engine aggregates — no attacker-influenced `db` param,
   unlike the S3 `backup` action):
   - **postgres:** `SELECT datname, pg_database_size(datname) FROM pg_database WHERE datistemplate
     = false` — the exact WHERE clause already in `DatabaseDiscoverer.java:74`; sum the rows.
   - **mysql / mariadb:** `SELECT table_schema, SUM(data_length + index_length) FROM
     information_schema.tables GROUP BY table_schema`, prepended on MySQL 8 with
     `SET SESSION information_schema_stats_expiry = 0` for fresh (not cached) stats (report `…:443`);
     the `SET` is omitted for MariaDB (no such session var).

3. **The transport is credential/peer-auth discovery, in a fixed precedence.**
   - **postgres — peer auth:** `sudo -u postgres psql -tA …`. The default Debian/Ubuntu local
     auth is `peer` for the `postgres` OS superuser over the unix socket, so no password is
     handled by this app. Requires per-action `sudo` (S5). Fallback: login-user `psql` (works only
     if the login user is a pg superuser or has `~/.pgpass`).
   - **mysql / mariadb — config-file creds:** try login-user `mysql` first (it auto-reads
     `~/.my.cnf` `[client]`); on auth failure, `sudo mysql --defaults-extra-file=/etc/mysql/debian.cnf`
     (the root-readable `debian-sys-maint` maintenance account). This app never stores or handles a
     DB password; it only invokes a client that resolves its own credentials from a file the
     operator controls.

4. **Physical size = `du` on the engine-reported data directory, verified against the catalog.**
   The catalog default (056's common-service default-folder catalog / `ServiceCatalog`: postgres
   `/var/lib/postgresql/<v>/…`, mysql/mariadb `/var/lib/mysql/`) is a hint; the **authoritative**
   data dir comes from the engine itself (`SHOW data_directory` for postgres, `SELECT @@datadir`
   for mysql/mariadb) in the same session as the logical query — "fingerprint → catalog → verify"
   (report `…:499`). Then `du -sbx` that directory under `timeout 120 nice -n19 ionice -c3`. These
   dirs are `0700`-owned by the engine's OS user, so the `du` needs the same `sudo`.

5. **Degrade and label without privilege (054 D3).** No `sudo` grant / no creds ⇒ logical and
   physical are **unavailable**, emitted as `permission-denied` at `confidence=low`, **never a fake
   `0`**. The context still appears (from discovery); its DB axes render `—`. The on-demand
   *re-probe-with-sudo* upgrade path is 057 real work — 058's size checks are simply proposed
   `sudo = true` by default because both engines' reliable local transport is inherently privileged.

## Implementation

### Where the code lands

Extend the **standalone branch** of `DatabaseDiscoverer` (`discovery/service/DatabaseDiscoverer.java`),
which already runs per engine when `command -v` + `systemctl is-active` say the engine is present
and is a systemd service. 056 supplies the dockerized-vs-standalone determination (cgroup /
compose-project check); 058 fires only for the **standalone** verdict. `DatabaseDiscoverer` gains,
alongside its existing `status`/`backup` proposals, **read-only MONITOR-check proposals** for
logical and physical size, landing `PENDING_APPROVAL` like every discovery proposal — approval is
where the operator accepts the `sudo` transport. It also classifies the standalone engine as a
**consumer** (`role = DATABASE`, `source = NATIVE`, following the existing spec-034/041 consumer model)
on the un-audited `app_port_list` JSON side-data seam, so 057's probing and 059's UI pick it up
with no re-approval (the same seam `refreshDiscoveredAppPortList` / `DiscoveryService.persist` :185
already uses; the column-widening migration is **056's**). **Cross-spec note:** that seam today
reconciles only the *docker* consumer channel (`proposal.appPortList()` vs `dockerConsumers`), so
055/056/057 must have added the **native-consumer classification channel** to `DiscoveryService.persist`
before/with 058 — 058 *populates* it, it does not create it.

### Probe scripts (S4-safe: constant `sh -c`, read-only, validated inputs)

All proposed as MONITOR checks with fixed argv; **param-free** so 057's client poll selects them
exactly as it selects the param-free docker MONITOR checks it already drives. No caller-controlled
structure; the SSH adapter POSIX-quotes each element (S4). Concretely:

- **postgres logical** (`sudo = true`):
  `sudo -u postgres psql -tAF, -c "SELECT datname, pg_database_size(datname) FROM pg_database WHERE datistemplate = false"`
  → `datname,bytes` lines; sum client-side.
- **postgres datadir + physical** (`sudo = true`):
  `sudo -u postgres psql -tAc "SHOW data_directory"` then `sudo du -sbx <datadir>` wrapped in
  `timeout 120 nice -n19 ionice -c3`.
- **mysql / mariadb logical** (`sudo = true`; login-user fallback drops `sudo`):
  `mysql -N -B -e "<SET…;> SELECT table_schema, SUM(data_length+index_length) FROM information_schema.tables GROUP BY table_schema"`
  — reusing `DatabaseDiscoverer.java:62-63`'s `mysql -N -B -e` idiom.
- **mysql / mariadb datadir + physical** (`sudo = true`):
  `mysql -N -B -e "SELECT @@datadir"` then `sudo du -sbx <datadir>` under the same `nice/ionice`
  wrapper.

**Data-dir validation.** The engine-returned data-directory string is bound into the `du` argv
only after it is validated as an **absolute path** (mirroring `ActionService.java:127-129`) —
no shell metacharacters, single POSIX-quoted argv element. It is an on-box, read-only target.

### Cadence

The size probes run on 057's **Slow tier** (hourly → daily, plus on-deploy; report `…:458`),
staggered, `nice/ionice`-bounded, cached with a timestamp — DB sizing is heavy engine + tree
work, never a fast-poll axis. 058 registers its checks into that tier; it does not define its own.

### Footprint integration (057 → spec-041 `computeOther`)

058 fills the standalone DB consumer's 057 **`{logicalBytes, physicalBytes}`** pair:

- **Physical → disk axis, conditionally.** The physical `du` bytes become the DB context's
  **disk** numerator, normalized **client-side** against `denom.diskBytes` (root/data-root FS total
  from `df -h`), exactly as `applyDockerReading`/`applyConsumerReading` do (`static/app.js` ~2790).
  Because `computeOther.attr(disk)` (`app.js:2386-2390`) blindly sums `c.disk` over named
  consumers, a populated DB disk % **auto-subtracts from OTHER** — correct **only if** measured
  against the root FS. **Guard:** attribute physical du to the disk axis **only when the data
  directory resides on the root/data-root filesystem** (cross-check with `df` / `findmnt -rn -o
  TARGET`); if it sits on a **separate mount**, report `physicalBytes` as a standalone display
  number but do **not** fold it into the root-FS disk axis, else it over-subtracts from OTHER
  (the single-denominator invariant, spec-041). Emit **raw bytes, never a pre-divided %**, and
  honest absence (null → `—`, never `0`).
- **Logical** is a DB-specific display number (the "state ×2" pair with physical), not a host-%
  axis; 059 renders it beside physical.

No `computeOther` edit belongs here — it reads `c.disk` generically; 058 only supplies the
numerator on the correct denominator.

### MCP surface, gate, and S9 — all stay green

- **No new MCP tool, no new run path.** 058 adds discovery-proposed MONITOR checks and a consumer
  classification only. The checks reach MCP through the existing `DiscoverRecipesTool` proposal
  path and land `PENDING_APPROVAL`; `run_action` (`RunActionTool`) remains the sole gate entry.
  `GateArchTest` (`recipe/GateArchTest.java`) stays green — nothing here references
  `ApprovalService` or a `*Repository` from `mcp/`, and no tool name contains "approve".
- **S9 (paths + creds off MCP).** The size checks' LITERAL argv necessarily carries absolute paths
  — the engine data directory and `--defaults-extra-file=/etc/mysql/debian.cnf`. These are
  **S9-secret internal literals** that `ListActionsTool` would otherwise echo, so they are covered
  by 055's **runtime path-withholding fix** in `ListActionsTool.tokenView` (regression-guarded by
  its source-scan arch test — the 028:224-227 deferred hardening: no MCP tool emits a raw absolute
  path); the MCP surface shows only the
  **engine basename identity** ("postgresql" / "mysql" / "mariadb"), never the datadir, the config
  path, or any credential. 058 introduces no new leak — it routes its path LITERALs through 055's
  sanitisation; 060 verifies this end-to-end. No DB password is ever handled, logged, or surfaced:
  the client binary resolves its own credentials from operator-controlled files.

## Known Gaps

- **Dockerized DB sizing is 057, not here.** 058 is exclusively the standalone (systemd-service)
  branch. The dockerized-vs-standalone determination (cgroup / compose-project) is 056's.
- **The DB metric-pair record shape and cadence tiers are 057** — 058 consumes them verbatim and
  only fills the standalone values; it does not define the pair or the tiers.
- **mongodb / redis and other engines are out of scope** — the catalog lists mongodb as
  near-future (report `…:509`); 058 covers only postgres / mysql / mariadb, matching D5.
- **The on-demand re-probe-with-sudo upgrade (054 D3) is 057 work.** 058 proposes its checks
  `sudo = true` and degrades-and-labels when the grant is absent, but does not build the UI/flow
  that re-runs a degraded reading at higher fidelity.
- **Non-default / non-peer postgres auth and mysql secrets injected only via mount/file** are a
  degrade case, not a solved one: if peer auth is disabled or `debian.cnf` is absent and no
  `~/.my.cnf` exists, logical size falls to `permission-denied` (report `…:446-447` flags the
  per-app credential-hint need as future work, not built here).
- **The UI surface for the standalone DB context is 059**; 058 supplies the data, not the screen.
- **No approval-gate, verb-contract, or `ActionSnapshot`-hash change** — the size checks are
  ordinary read-only MONITOR actions; verbs/hash are spec-053/055/060 scope.

## Implementation Notes

Built on `moacyrricardo/bol-887-cpt-058-standalone-database-sizing`, stacked on the
`moacyrricardo/integration-057-061-062` base (PR #87). How the build differed from the spec:

- **Landed as two `sh -c` MONITOR checks per engine on `DatabaseDiscoverer`** — `db logical size`
  and `db physical size`, `sudo=true`, param-free, appended to each engine recipe alongside
  `status`/`backup`. This mirrors 057's dockerized `DockerComposeDiscoverer` DB-size pair exactly,
  differing only in transport (host-side vs `docker exec`). No migration (existing token/side-data
  seams suffice; scripts stay under the `token_value(1024)` bound).
- **`sudo=true` reconciled with Decision 3's transport precedence.** The action's `sudo=true` flag
  (Decision 5) means the executor runs the script under an outer `sudo -n`; the transport therefore
  runs privileged, so mysql/mariadb use **root socket auth** as the primary path (the root-readable
  `--defaults-extra-file=/etc/mysql/debian.cnf` as the fallback) and postgres keeps the explicit
  `sudo -u postgres` for peer auth. This realizes D3's precedence at the privilege level D5 mandates;
  no DB password is ever handled.
- **Logical rows summed in-script (`awk`)** rather than client-side, so the emitted `logicalBytes=`
  single value matches the output shape 057's docker path (and 059's parser) already consume. The
  query text still matches Decision 2 verbatim (`SELECT datname, pg_database_size(datname) …`).
- **Physical probe emits `physicalBytes` + an `onRootFs` boolean** (via `findmnt -rno TARGET -T`),
  keeping the spec-041 root/data-root FS gate in 058's data output; the actual disk-axis fold and all
  presentation are 059's (the spec forbids a `computeOther` edit here). The datadir is resolved at
  runtime and **never echoed** (S9).
- **Deferred: the NATIVE DATABASE consumer classification on the `app_port_list` seam.** The spec's
  cross-spec note assigns creation of the *native-consumer classification channel* to 055/056/057
  ("058 populates it, it does not create it"); that channel is **absent** from `DiscoveryService.persist`
  in the integration branch (only the docker-consumer channel exists; `AppPortItem` has no
  `role`/`source` field). 058 delivers the size probes that feed 057's `{logicalBytes, physicalBytes}`
  pair; the consumer-card wiring is blocked on that upstream channel and is tracked on BOL-887 for a
  human decision (add the channel under 055/056/057, or a new spec).
