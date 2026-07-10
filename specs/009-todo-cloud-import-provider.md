# 009 — Cloud import (discovery provider)

> **PARKED — fast-follow (decision 2026-07-10).** Build `001`–`008` (the core:
> register → recipe → gate → run → discover → MCP) end-to-end first, then return
> to deepen this spec. Left intentionally at sketch depth until scheduled. This
> also lets S3 (host-key verification) be revisited before importing dynamic,
> IP-recycled cloud hosts.

## Context

Registering machines one at a time does not scale to an existing fleet. This spec
adds **cloud import**: pull instances (and their cloud tags) from a provider
account and register them as machines in bulk. It is **read-only against the
cloud** — it never mutates the provider side. This feature is an addition beyond
the original scope (register / recipes / custom scripts); it is sequenced last
and could ship as a fast-follow rather than v1.

## Decision

- **`CloudProvider` port** in `cloud`, implementations added in order:
  `AwsCloudProvider` first, then `GcpCloudProvider`, `MagaluCloudProvider`.
- Import lists instances via read-only provider APIs (`Describe*`/list), maps each
  to a `Machine` (host from its address/DNS, `loginUser` from convention/config),
  and carries **cloud tags** across as machine tags.
- Credentials come from the ambient environment / instance profile (ARCH.md S6).
- Triggered from UI and the `discover_cloud(provider)` MCP tool (008); both call
  the same `cloud` service.

## Implementation

- `cloud/service`: `CloudImportService.import(provider)` → registers/updates
  machines via `MachineService`.
- `cloud/*Provider` adapters behind the port; AWS SDK types stay inside the
  adapter (business code depends only on `CloudProvider`).
- Idempotent import: re-importing updates existing machines (matched by
  provider instance id) rather than duplicating.

## Known Gaps

- **S6 — cloud credentials are ambient** (env/instance profile), not scoped or
  stored as secrets. Before multi-tenant/deployed use: restrict to read-only
  permissions and store per-provider config as secrets, never in the DB.
- **Interacts with S3 (003).** Imported hosts are dynamic and their public IPs
  are recycled aggressively by providers; importing them while host-key
  verification is disabled widens the impersonation surface. This is the concrete
  case that should trigger revisiting S3.
- Address selection (public vs private IP / DNS) and `loginUser` conventions per
  provider need per-provider defaults; treated as adapter detail here.
