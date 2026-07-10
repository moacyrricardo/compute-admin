# 006 — Recipe auto-discovery

## Context

Registering machines by hand and hand-writing every action is tedious. This spec
adds **recipe discovery**: SSH into a known machine, detect installed services,
and **propose** recipes + default actions. It never mutates the box and never
auto-approves — proposals land in `DRAFT`/`PENDING_APPROVAL` for a human to
review through the 004 gate.

## Decision

- **`RecipeDiscoverer` port** in `discovery`, one implementation per built-in
  service type: `NginxDiscoverer`, `DockerDiscoverer`, `DatabaseDiscoverer`
  (mysql/mariadb/postgres), `CronDiscoverer`.
- Each discoverer runs a **bounded, read-only probe** over `SshExecutor` (e.g.
  detect the binary/service, list sites/containers/units) and maps findings to
  proposed `Recipe` + `Action` definitions with sensible default templates and
  param schemas.
- **Proposals are never approved and never executed** by discovery. They are
  created in `DRAFT`/`PENDING_APPROVAL` only.

## Implementation

- `discovery/service`: `DiscoveryService.discover(machineId)` fans out to the
  registered discoverers and persists proposals via `RecipeService`/
  `ActionService`.
- Each `*Discoverer` owns its fixed probe commands (checked into source, not
  agent-supplied) and its proposed-action catalog.
- `discovery/api`: endpoint to trigger discovery and return proposals; the MCP
  `discover_recipes` tool (008) calls the same service.

## Known Gaps

- **Discovery probes are un-gated command execution.** `discover_recipes` runs
  detection commands on the target with no approval — an intentional exception to
  "only APPROVED actions run." Probes are therefore restricted to a **fixed,
  read-only, source-controlled** command set; free-form or agent-supplied probes
  are out of scope and must never be added here.
- **Discovery results are attacker-influenced input.** A compromised or spoofed
  target (see S3, 003) returns service names/paths that become **proposed
  templates** shown to a human. The 004 approval step is the mitigation — the
  human must be able to read the proposed template before approving.
