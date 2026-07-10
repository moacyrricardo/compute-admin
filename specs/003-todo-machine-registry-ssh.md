# 003 — Machine registry, tagging, app keypair & SSH adapter

## Context

Everything operates on **machines**. This spec adds the machine registry, tags,
the app-owned SSH keypair, and the `SshExecutor` port that later specs (run,
discovery) depend on. It is the first spec with a real schema, an Envers-audited
entity, and an adapter-behind-a-port.

## Decision

- **`Machine`** entity: `host`, `port`, `loginUser`, `status` (connection
  state), timestamps. `Machine (N)─(N) Tag` with free-form string tags.
- **App keypair lifecycle** owned by the `ssh` module: generate an **ed25519**
  keypair on first boot at `./data/id_ed25519` (`chmod 600`), expose the **public
  key** so the operator can install it into each target's `authorized_keys`.
- **`SshExecutor` port** in `ssh`: `MinaSshExecutor` (real, Apache MINA SSHD) and
  `LocalDevSshExecutor` (dev, runs against localhost / no-op). Business code
  depends on the port, never on MINA types. Swap by profile-scoped beans.
- **No SSH host-key verification** yet (ARCH.md S3) — accept any host key — but
  the schema **leaves room for a pinned host-key column** so TOFU pinning is a
  later additive change, not a migration rewrite.

## Implementation

- `machine/model`: `Machine`, `Tag`, join table. `machine/repository`:
  `MachineRepository`, `TagRepository`. `machine/service`: `MachineService`
  (register, tag, lookup, list-by-tag). `machine/api`: `MachineRS` under `/api`.
- Migration `V2__machine.sql`: `machine`, `tag`, `machine_tag`, plus a nullable
  `pinned_host_key` column on `machine` (unused until S3 is addressed).
- `ssh`: `SshExecutor` interface — `ExecResult exec(MachineRef, List<String> argv,
  boolean sudo)` and a streaming variant used by 005. `MinaSshExecutor`,
  `LocalDevSshExecutor`. `KeyService` for generate-on-first-boot + public-key
  exposure (`GET /api/ssh/public-key`, and later an MCP resource).
- `ConnectivityCheckJob` (scheduled `*Job`) updates `Machine.status`.
- `Machine` is **Envers-audited** (revision infra lands with the audit config in
  004; entity is annotated here).

## Known Gaps

- **S3 — no host-key verification.** The `pinned_host_key` column is reserved but
  unused; any host can impersonate a target. Revisit before any untrusted network
  path, and note this interacts poorly with cloud import (009), whose hosts are
  dynamic and may be recycled.
- **S2 — private key stored unencrypted** at `./data/id_ed25519`; filesystem
  perms are the only boundary.
- The H2 file DB itself (fleet inventory) is unencrypted at rest; not covered by
  S2, which only names the key.
