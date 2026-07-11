# 003 — Machine registry, tagging, app keypair & SSH adapter

> Status: **doing** — branch `moacyrricardo/spec-003-machine-registry-ssh`
> (stacked on `moacyrricardo/spec-011-user-accounts-auth-ownership`). Linear is
> blocked for this repo, so no issue identifier.

## Context

Everything operates on **machines**. This spec adds the machine registry, tags,
the app-owned SSH keypair, the `SshExecutor` port later specs depend on, and —
because `Machine` is the first `@Audited` entity — the **audit-module
foundation** (Envers revision entity + actor listener + `revinfo`). Conventions
per ARCH.md "Code conventions".

## Decision

- **`Machine`** keyed by app-assigned String UUID; owned by an `AppUser` (011);
  `host`, `port`, `loginUser`, `status`, a reserved nullable `pinnedHostKey`,
  `createdAt`/`updatedAt`. Every operation is scoped to the current user.
- **`Tag`** is an entity, unique **per owner**; `Machine (N)─(N) Tag` via
  `machine_tag`.
- **App keypair** owned by `ssh`: generate an **ed25519** pair on first boot at
  `./data/id_ed25519` (`chmod 600`) if absent; expose the public key.
- **`SshExecutor` port** with a real MINA impl; the dev/verify flow drives the
  **real** path against a throwaway Docker sshd container.
- **No host-key verification** (S3), but the schema reserves `pinned_host_key`.
- **Audit foundation** wired so `Machine` revisions record the ambient actor.

## Implementation

**`machine/model`.**
- `Machine` — `@Audited`. `String id = UUID.randomUUID().toString()` (`@Column(length=36)`);
  `@ManyToOne AppUser owner` (011); `host`; `int port` (default 22); `loginUser`;
  `MachineStatus status` (`@Enumerated(STRING)`, `UNKNOWN | ONLINE | OFFLINE |
  UNREACHABLE`, default `UNKNOWN`); `String pinnedHostKey` (nullable, unused until
  S3); `Instant createdAt/updatedAt`. `@ManyToMany Set<Tag> tags`. Unique
  `uq_machine_owner_host_port_user (owner_id, host, port, login_user)`.
- `Tag` — `String id`, `@ManyToOne AppUser owner`, `String name`, unique per owner
  (`uq_tag_owner_name (owner_id, name)`). Un-audited (labels only).
- `MachineStatus` enum.

**`machine/repository`.** `MachineRepository extends JpaRepository<Machine,String>`
with `List<Machine> findByOwnerId(String)` and
`findByOwnerIdAndTags_Name(String ownerId, String tag)`; `TagRepository` with
`Optional<Tag> findByOwnerIdAndName(String ownerId, String name)`.

**`machine/service`.**
- `MachineService` — every method resolves `CurrentUser.require()` and scopes to
  the owner. `register(RegisterMachineInput)` (validate host non-blank, port
  1–65535, loginUser non-blank; sets `owner = current user`; `@Transactional`;
  pre-checks the `uq_machine_owner_host_port_user` key and throws
  `MachineAlreadyRegisteredException` — 409 — so re-adding a host is a clean
  conflict, not the raw constraint-violation 500),
  `tag(id, Set<String>)` (get-or-create the current user's tags), `untag(id,
  name)`, `list(String tag)` (the current user's machines, all or by tag),
  `requireMachine(id)` (must belong to the current user, else
  `MachineNotFoundException` — 404, existence not leaked). `RegisterMachineInput`
  is a service-input record.
- `MachineNotFoundException` (Javadoc: mapped to 404).
- `MachineAlreadyRegisteredException` (Javadoc: mapped to 409).

**`machine/api`.**
- `MachineRS` (`@Component @Path("/machines")`, `@Secured`): `POST /` register; `GET /?tag=`
  list; `GET /{id}`; `POST /{id}/tags`; `DELETE /{id}/tags/{name}`. Returns
  `MachineDtos` records, throws on failure.
- `MachineDtos`: `RegisterMachineRequest(host, port, loginUser)`,
  `TagRequest(Set<String> names)`, `MachineView.of(Machine)` (id, host, port,
  loginUser, status, tags).

**`ssh` (port + adapter).**
- `SshExecutor` — `ExecResult exec(SshTarget t, List<String> argv, boolean sudo)`
  and `void execStreaming(SshTarget t, List<String> argv, boolean sudo, OutputSink
  sink)` (used by 005). `SshTarget(host, port, loginUser)`, `ExecResult(int
  exitCode, String stdout, String stderr)`, `OutputSink` (callback for
  stdout/stderr chunks + completion).
- `MinaSshExecutor` — **default** bean, Apache MINA SSHD client, authenticates
  with the app keypair, `AcceptAllServerKeyVerifier` (S3). `sudo` prefixes the
  argv with `sudo -n` (passwordless, S5). SSH `exec` inherently runs a single
  command string through the remote shell, so the executor POSIX-**single-quotes**
  each argv element into that string — every typed parameter stays one literal
  argument and cannot break out into an injection (S4). Only `LocalDevSshExecutor`
  passes true argv (via `ProcessBuilder`, no shell); the `SshExecutor` port
  contract is *argv-in, injection-safe* and the two adapters honour it by
  different means.
- `LocalDevSshExecutor` — `localssh` profile only; runs argv against localhost for
  offline work. The normal dev/verify path uses `MinaSshExecutor` against the
  Docker container.
- `KeyService` — generate-on-first-boot; `publicKeyOpenSsh()`, `fingerprint()`.
- `SshRS` (`@Path("/ssh")`): `GET /public-key` → `SshDtos.PublicKey(publicKey,
  fingerprint)` (also exposed as an MCP resource in 008).

**`machine/job/ConnectivityCheckJob`** — `@Scheduled(cron="${ca.connectivity.cron:0 */5 * * * *}")`,
runs a trivial `true` over `SshExecutor` for **every** machine (system-scoped,
not per-user) and updates `status` **only when the probed result differs** from
the stored status (a liveness probe is not a config edit, so an unchanged cycle
must leave the machine clean and open no `machine_aud` revision — no `via = SYSTEM`
audit noise). Uses a repository call that bypasses the owner filter; the audited
revision written on a real change records `via = SYSTEM`. `config/SchedulingConfig`
(`@EnableScheduling @EnableAsync`).

**`audit` module (foundation).** Uses the identity context from 011.
- `AuditRevision` — `@RevisionEntity(CurrentUserRevisionListener.class)
  @Table(name="revinfo")`, columns `rev` (`@GeneratedValue IDENTITY`), `timestamp`,
  `user_id` (nullable), and `via`.
- `CurrentUserRevisionListener implements RevisionListener` — reads
  `CurrentUser.optional()`, recording `userIdOrSystem()` + `via` (defaulting to
  `Via.SYSTEM`).

**Migration `V3__machine.sql`.** `machine` (with `owner_id` → `app_user`), `tag`
(with `owner_id`), `machine_tag`; the `revinfo` table; `machine_aud` (all audited
columns + `rev`/`revtype`/`revend`, the last two FKs to `revinfo` per the validity
audit strategy); H2 dialect; named `uq_`/`fk_` constraints;
spec-referencing header comment. (`app_user` exists from 011 `V2`.)

**Dev target (project `CLAUDE.md`).** Document a throwaway sshd container recipe
(e.g. `docker run … linuxserver/openssh-server`) with the app public key
installed in `authorized_keys`, and how the verify flow connects to it.

**Tests.**
- `MachineServiceTest` — `@DataJpaTest` + `@AutoConfigureTestDatabase(replace=NONE)`
  + `@Import(MachineService.class)`: register scopes to owner, dedup tags per
  owner, list-by-tag, `requireMachine` on another user's machine → 404,
  re-registering the same host/port/loginUser → `MachineAlreadyRegisteredException`.
- `MachineAuditTest` — `@DataJpaTest` + `@Import(MachineService.class)`: a UI-scoped
  `Machine` write opens a `machine_aud` revision whose `revinfo` row is stamped with
  the acting `user_id` and `via = UI`; a system-scoped write stamps `user_id = null`
  and `via = SYSTEM`. Reads back via the Envers `AuditReader`.
- `MachineWebTest` — `@SpringBootTest(RANDOM_PORT)`: authenticate via the dev
  Google bypass (011), register + list round-trip; user B gets 404 on user A's
  machine. `@TestConfiguration @Bean @Primary` fake `SshExecutor`.
- `MinaSshExecutorTest` — pure unit test of `assembleCommand`: each argv element is
  POSIX single-quoted, spaces/quotes/shell metacharacters (`;`, `$(…)`, backticks)
  stay one literal argument (S4), and `sudo` prepends bare `sudo -n`.
- `SshKeyTest` — key generated on first boot, public key/fingerprint exposed.

## Known Gaps

- **S3 — no host-key verification.** `pinned_host_key` reserved but unused; any
  host can impersonate a target. Revisit before any untrusted path — and note it
  interacts badly with cloud import (009), whose hosts are dynamic/recycled.
- **S2 — private key unencrypted** at `./data/id_ed25519`; filesystem perms are
  the only boundary. The H2 file DB (fleet inventory) is likewise unencrypted at
  rest — broader than S2, which names only the key.
- **S5 — `sudo -n` assumes passwordless sudo** on the target.

## Implementation Notes (done)

Branch `moacyrricardo/spec-003-machine-registry-ssh` (PR #6). Implemented per spec.
How it differed / what was resolved during review:

- **Envers validity strategy adopted** (`audit_strategy=ValidityAuditStrategy`,
  `revend` column on `machine_aud`) to match ARCH.md, after an eval flagged an
  initial default-strategy implementation. This sets the audit foundation every
  later `_aud` table inherits.
- **S4 wording reconciled.** `MinaSshExecutor` hands one POSIX-single-quoted
  command string to the SSH exec channel (inherent to SSH exec) — injection-safe
  via quoting, verified by `MinaSshExecutorTest`. The "never a shell line" phrasing
  in ARCH S4 / gate-point-5 was corrected to "safely shell-escaped single line."
- Added a **409 `MachineAlreadyRegisteredException`** on duplicate host/port/user.
- **Deferred to fresh specs:** `ConnectivityCheckJob`'s fleet-wide SSH I/O inside a
  single transaction, and per-`exec()` SSH client creation → belong to the
  execution-engine design (spec 005). The **ambient-actor propagation gap on the
  MCP tool thread** (`ScopedValue` is thread-confined; the MCP SDK dispatches off
  the request thread) must be solved and tested when spec 008 wires real MCP tools.
