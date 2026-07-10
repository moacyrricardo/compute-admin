# 003 — Machine registry, tagging, app keypair & SSH adapter

## Context

Everything operates on **machines**. This spec adds the machine registry, tags,
the app-owned SSH keypair, the `SshExecutor` port later specs depend on, and —
because `Machine` is the first `@Audited` entity — the **audit-module
foundation** (Envers revision entity + actor listener + `revinfo`). Conventions
per ARCH.md "Code conventions".

## Decision

- **`Machine`** keyed by app-assigned String UUID; `host`, `port`, `loginUser`,
  `status`, a reserved nullable `pinnedHostKey`, `createdAt`/`updatedAt`.
- **`Tag`** is an entity (unique `name`); `Machine (N)─(N) Tag` via `machine_tag`.
- **App keypair** owned by `ssh`: generate an **ed25519** pair on first boot at
  `./data/id_ed25519` (`chmod 600`) if absent; expose the public key.
- **`SshExecutor` port** with a real MINA impl; the dev/verify flow drives the
  **real** path against a throwaway Docker sshd container.
- **No host-key verification** (S3), but the schema reserves `pinned_host_key`.
- **Audit foundation** wired so `Machine` revisions record the ambient actor.

## Implementation

**`machine/model`.**
- `Machine` — `@Audited`. `String id = UUID.randomUUID().toString()` (`@Column(length=36)`);
  `host`; `int port` (default 22); `loginUser`; `MachineStatus status`
  (`@Enumerated(STRING)`, `UNKNOWN | ONLINE | OFFLINE | UNREACHABLE`, default
  `UNKNOWN`); `String pinnedHostKey` (nullable, unused until S3);
  `Instant createdAt/updatedAt`. `@ManyToMany Set<Tag> tags`. Unique
  `uq_machine_host_port_user (host, port, login_user)`.
- `Tag` — `String id`, `String name` unique (`uq_tag_name`). `@Audited` optional;
  keep it un-audited (labels only).
- `MachineStatus` enum.

**`machine/repository`.** `MachineRepository extends JpaRepository<Machine,String>`
with `List<Machine> findByTags_Name(String tag)`; `TagRepository` with
`Optional<Tag> findByName(String)`.

**`machine/service`.**
- `MachineService` — `register(RegisterMachineInput)` (validate host non-blank,
  port 1–65535, loginUser non-blank; `@Transactional`), `tag(id, Set<String>)`
  (get-or-create tags), `untag(id, name)`, `list(String tag)` (all or by tag),
  `requireMachine(id)` → `MachineNotFoundException` (404). `RegisterMachineInput`
  is a service-input record.
- `MachineNotFoundException` (Javadoc: mapped to 404).

**`machine/api`.**
- `MachineRS` (`@Component @Path("/machines")`): `POST /` register; `GET /?tag=`
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
  argv with `sudo -n` (passwordless, S5). argv passed as discrete arguments,
  never a shell line (S4).
- `LocalDevSshExecutor` — `localssh` profile only; runs argv against localhost for
  offline work. The normal dev/verify path uses `MinaSshExecutor` against the
  Docker container.
- `KeyService` — generate-on-first-boot; `publicKeyOpenSsh()`, `fingerprint()`.
- `SshRS` (`@Path("/ssh")`): `GET /public-key` → `SshDtos.PublicKey(publicKey,
  fingerprint)` (also exposed as an MCP resource in 008).

**`machine/job/ConnectivityCheckJob`** — `@Scheduled(cron="${ca.connectivity.cron:0 */5 * * * *}")`,
runs a trivial `true` over `SshExecutor` per machine and updates `status`.
`config/SchedulingConfig` (`@EnableScheduling @EnableAsync`).

**`audit` module (foundation).**
- `AuditRevision` — `@RevisionEntity(CurrentActorRevisionListener.class)
  @Table(name="revinfo")`, columns `rev` (`@GeneratedValue IDENTITY`), `timestamp`,
  and `actor` (from `Actor`).
- `CurrentActorRevisionListener implements RevisionListener` — reads
  `CurrentActor.optional()`, defaulting to `Actor.SYSTEM`.

**Migration `V2__machine.sql`.** `machine`, `tag`, `machine_tag`; the `revinfo`
table; `machine_aud` (all audited columns + `rev`/`revtype`); H2 dialect; named
`uq_`/`fk_` constraints; spec-referencing header comment.

**Dev target (project `CLAUDE.md`).** Document a throwaway sshd container recipe
(e.g. `docker run … linuxserver/openssh-server`) with the app public key
installed in `authorized_keys`, and how the verify flow connects to it.

**Tests.**
- `MachineServiceTest` — `@DataJpaTest` + `@AutoConfigureTestDatabase(replace=NONE)`
  + `@Import(MachineService.class)`: register, dedup tags, list-by-tag,
  require→404.
- `MachineWebTest` — `@SpringBootTest(RANDOM_PORT)`: register + list round-trip; a
  `@TestConfiguration @Bean @Primary` fake `SshExecutor`.
- `SshKeyTest` — key generated on first boot, public key/fingerprint exposed.

## Known Gaps

- **S3 — no host-key verification.** `pinned_host_key` reserved but unused; any
  host can impersonate a target. Revisit before any untrusted path — and note it
  interacts badly with cloud import (009), whose hosts are dynamic/recycled.
- **S2 — private key unencrypted** at `./data/id_ed25519`; filesystem perms are
  the only boundary. The H2 file DB (fleet inventory) is likewise unencrypted at
  rest — broader than S2, which names only the key.
- **S5 — `sudo -n` assumes passwordless sudo** on the target.
