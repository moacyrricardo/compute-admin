# 006 — Recipe auto-discovery

> Status: **done** — branch `moacyrricardo/spec-006-recipe-auto-discovery`.
> Linear is BLOCKED for this repo, so there is no issue identifier.

## Context

Hand-authoring every action is tedious. This spec adds **recipe discovery**: SSH
into a known machine with **fixed, read-only probes**, detect installed services,
and **propose** recipes + a **curated core** action set for the human to review
through the 004 gate. It never mutates the box and never auto-approves —
proposals land in `PENDING_APPROVAL`.

## Decision

A `RecipeDiscoverer` port with one impl per built-in service type. Each runs a
small source-controlled read-only probe set and proposes a **curated** action
catalog (chosen scope: useful but reviewable — not minimal, not full-lifecycle).
Discovered names/paths become `ALLOWED_SET` values on the proposed params.

## Implementation

**`discovery/RecipeDiscoverer` port** — `List<ProposedRecipe> discover(Machine m,
SshExecutor ssh)`; `ProposedRecipe(type, name, List<ProposedAction>)`,
`ProposedAction(name, sudo, argTokens, paramDefs)` reusing the 004 shapes. Probe
commands are **fixed constants in source**, never agent-supplied.

**Discoverers + curated catalog** (`discovery/service`). All actions land
`PENDING_APPROVAL`; mutating ones are proposed but gated.
- `NginxDiscoverer` — probe: `command -v nginx`, `nginx -t`, `ls
  /etc/nginx/sites-available` + `sites-enabled`. Actions: **test-config**
  (`nginx -t`), **reload** (`systemctl reload nginx`, sudo), **restart**
  (`systemctl restart nginx`, sudo), **enable-site** (`ln -s
  /etc/nginx/sites-available/{site} /etc/nginx/sites-enabled/`, sudo; `site`
  ALLOWED_SET from discovered sites), **disable-site** (remove the symlink, sudo;
  `site` ALLOWED_SET from enabled sites).
- `DockerDiscoverer` — probe: `command -v docker`, `docker ps
  --format '{{.Names}}'`. Actions: **ps**, **restart/stop/start container**
  (`container` ALLOWED_SET from discovered names), **logs** (`container`
  ALLOWED_SET; `--tail` INT_RANGE).
- `DatabaseDiscoverer` — probe: detect `mysql`/`mariadb`/`psql` binaries +
  service status; list databases read-only. Actions: **status** (`systemctl
  status <svc>`), **dump/backup** (`mysqldump`/`pg_dump` of `db` ALLOWED_SET from
  discovered DBs → a fixed backup dir).
- `CronDiscoverer` — probe: `crontab -l` (login user), `ls /etc/cron.d`. Action:
  **list**. (Add/remove cron entries are the deferred "broad" scope — not v1.)

**`discovery/service/DiscoveryService.discover(machineId)`** — resolves the
machine, runs each registered discoverer over `SshExecutor`, and persists
proposals as `Recipe` + `Action` (state `PENDING_APPROVAL`) via
`RecipeService`/`ActionService`. Never approves; never issues a mutating command.

**`discovery/api`** — `POST /api/machines/{id}/discover` (`@Secured`; the machine
must belong to the current user, else 404) → the proposals (`DiscoveryDtos`). The
MCP `discover_recipes` tool (008) calls the same service as the token's user.

**Tests.**
- Per-discoverer unit tests with a fake `SshExecutor` returning canned probe
  output → assert the proposed recipes/actions and that ALLOWED_SET values come
  from probe output.
- `DiscoveryServiceTest`: proposals persist as `PENDING_APPROVAL`; no approve
  call; no mutating command is ever sent (assert against the fake executor's
  recorded argv).

## Known Gaps

- **Discovery probes are un-gated command execution** — an intentional exception
  to "only APPROVED actions run." Probes are therefore restricted to a **fixed,
  read-only, source-controlled** set; free-form or agent-supplied probes are out
  of scope and must never be added.
- **Discovery results are attacker-influenced input** — a compromised/spoofed
  target (S3) returns names/paths that become proposed ALLOWED_SET values and
  templates. The 004 approval step is the mitigation; the human must be able to
  read the proposed action before approving.

## Implementation Notes

Implemented as specified. The `RecipeDiscoverer` port, the four built-in
discoverers (`Nginx`, `Docker`, `Database`, `Cron`), `DiscoveryService`, and the
`POST /api/machines/{id}/discover` REST surface all landed with per-discoverer
unit tests (fake `SshExecutor`) plus a `DiscoveryServiceTest` and a
`DiscoveryWebTest`. Proposals persist as `PENDING_APPROVAL` via
`RecipeService`/`ActionService` (never a repository), preserving ownership
scoping and the 004 gate; `DiscoveryService` never approves and never sends a
mutating command.

Divergences / choices made during coding:

- Probes were factored into a shared `Probes` helper (`commandExists`, `lines`,
  `target`) and proposal builders into a `Proposals` helper (`literal`, `param`,
  `allowedSet`) rather than inlining probe/arg-token construction in each
  discoverer — keeps the discoverers to their curated catalog.
- The `Database` discoverer excludes engine-internal schemas
  (`information_schema`, `performance_schema`, `mysql`, `sys`, and
  `datistemplate` postgres templates) from the discovered `db` ALLOWED_SET, and
  only proposes the `backup` action when at least one non-system database was
  found. Fixed backup dir: `/var/backups/compute-admin`.

**Change division.** No `CONTRIBUTING.md` in this repo, so the cross-project
default applies. The branch split cleanly into one logical concern per commit —
`todo → doing`, port + proposal shapes, discoverers + service, REST surface +
DTOs, tests — and the tree compiles at each. No API-module diff subsection: this
repo's `CLAUDE.md` `## API Modules` records **none**.

### Deferred (new architectural concerns for fresh specs)

- **Discovery is not idempotent.** Re-running `POST /discover` on the same
  machine creates duplicate `nginx`/`docker`/etc. recipes and actions —
  `RecipeService.create` enforces no per-machine name uniqueness. A fresh spec
  should define re-discovery semantics (dedup / replace / merge).
- **Probes run inside the persistence transaction.** `DiscoveryService.discover`
  is `@Transactional` and runs all SSH probes (network I/O) inside the DB
  transaction, holding it open for the full multi-discoverer round-trip. A fresh
  spec should run probes outside the persistence transaction, especially before
  any non-local use.
- **Backup output filename is fixed per engine.** The `Database` discoverer's
  `backup` action uses a fixed literal output filename per engine
  (`mysql-backup.sql` / `postgres-backup.sql`) regardless of the chosen `db`, so
  backups of different databases (and repeated runs) overwrite the same file. A
  future refinement could template the filename from the `db` param.
