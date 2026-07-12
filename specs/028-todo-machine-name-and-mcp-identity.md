# 028 — Machine name & MCP identity hardening

> **Status: todo.** Branch `moacyrricardo/spec-028-machine-name-mcp-identity`
> (cut from `main`). Linear is blocked for this repo, so there is no issue id; the
> implementing commits use `spec-028` subjects.
>
> Tracks ARCH.md **S9** (MCP infra exposure). This spec is the decision of record
> for closing it; S9 flips to resolved when 028 ships.

## Context

The MCP surface leaks raw machine infrastructure detail into the LLM context. A
user's own agent (and, transitively, the model provider and any MCP-layer logging)
receives IP addresses / hostnames, SSH login users, and ports that it never needs
to *operate* — every operation already addresses a machine by its opaque
`machineId`, not by its network coordinates.

Two concrete leak sites, in the tool **output**:

- **`list_machines`** — `ListMachinesTool.summarize`
  (`src/main/java/com/iskeru/computeadmin/mcp/ListMachinesTool.java:89-96`) returns a
  map of `id`, **`host`**, **`port`**, **`loginUser`**, `status` for every machine.
  So a bare listing hands the agent the full SSH coordinates of the whole fleet.
- **`register_machine`** — `RegisterMachineTool.call`
  (`src/main/java/com/iskeru/computeadmin/mcp/RegisterMachineTool.java:63-70`) echoes
  `id`, **`host`**, **`port`**, **`loginUser`**, `status` back in its response, so the
  address the agent just supplied is reflected straight back into the transcript.

The infra also lives on the model and the single DTO that both surfaces share:

- `Machine` (`src/main/java/com/iskeru/computeadmin/machine/model/Machine.java:65-72`)
  carries `host`, `port`, `loginUser` and **has no human-facing name today** — `host`
  is the only human-readable identifier, which is exactly the value we don't want on
  the MCP surface.
- `MachineDtos.MachineView`
  (`src/main/java/com/iskeru/computeadmin/machine/api/MachineDtos.java:31-42`) is a
  single view exposing `id/host/port/loginUser/status/tags`, used by **both** the UI
  REST resource (`MachineRS`, `src/main/java/com/iskeru/computeadmin/machine/api/MachineRS.java`)
  **and**, indirectly, mirrored by the ad-hoc maps the MCP tools build. There is no
  separation between "what a human in an authenticated browser session sees" and
  "what flows over MCP into an LLM".

**Scope of the risk.** This is *not* a cross-user leak — every read is owner-scoped
(`MachineService.list` / `requireMachine` resolve `CurrentUser.require()`, a not-owned
id is 404). It is needless **infra/topology exposure** of the owner's own machines
into the LLM context and any downstream logging. The write/run tools (`run_action`,
`discover_recipes`, `tag_machine`) are already clean: they take and echo `machineId`
only (`RunActionTool.java:46`, `DiscoverRecipesTool.java:31`, `TagMachineTool.java:32,72`).
The leak is confined to the **listing and registration output**.

The other gap this exposes: with `host` removed from the MCP surface, an agent has
**no friendly label** to identify a machine by. Today it would have to reason about
opaque UUIDs. So the fix has two halves — hide the infra, and give the machine a
user-provided **name** to stand in as the MCP-facing identifier.

## Decision

Five locked decisions:

1. **Add `Machine.name`, user-provided at registration.** A human-meaningful label
   (e.g. `web-prod-1`), supplied by the user when the machine is registered. It
   becomes the MCP-facing identifier. **Unique per owner**, mirroring `Tag`'s
   `uq_tag_owner_name` per-owner uniqueness (`Tag.java:27-29`) — two users may each
   have a `web-prod-1`, but one user cannot have it twice.

2. **Split the DTO into two views.**
   - The **MCP** view exposes **`id` + `name` + `tags` + `status` ONLY**. It MUST
     omit `host`, `port`, `loginUser`.
   - The **UI** view (`MachineView`, authenticated human browser session) keeps the
     **full** detail — `id/name/host/port/loginUser/status/tags`. The human still
     needs to see the address they registered.

3. **`register_machine` over MCP still ACCEPTS `host` (plus `name`, `loginUser`,
   `port`) as INPUT** — the agent must supply an address to register a machine — but
   its **RESPONSE MUST NOT echo `host`/`port`/`loginUser`**. It returns the MCP view
   (`id` + `name` + `status`, and `tags`). So an address flows *in* (the agent already
   knows it) but never flows *back out* through MCP.

4. **Operations stay on `machineId`** — unchanged. `run_action`, `discover_recipes`,
   `tag_machine` continue to address machines by the opaque id. `name` is an
   additional human/agent-facing label, not a new operational key.

5. **ARCH.md gains deferred-risk register item S9** — "MCP infra exposure": machine
   `host`/`port`/`loginUser` were surfaced over MCP; resolved by spec 028 (MCP
   identifies machines by `id` + `name` only; `host`/`port`/`loginUser` stay
   UI-only). Row added now, marked resolved-by-028 (open until 028 builds).

## Implementation

Referencing the real classes. **Nothing in `src/` changes in this spec — it is a
catalog decision;** the steps below are the build contract for the implementing
branch.

### Model + migration

- **`Machine.name`** — add a `@Column(nullable = false, length = 255) private String name;`
  field to `Machine` (`machine/model/Machine.java`). It is `@Audited` like the other
  config columns (it sits inside the class body already covered by the class-level
  `@Audited`), so `machine_aud` gains a matching `name` column.
- **Per-owner uniqueness** — add a table-level unique constraint
  `uq_machine_owner_name (owner_id, name)` to `Machine`'s `@Table(uniqueConstraints = …)`,
  alongside the existing `uq_machine_owner_host_port_user` (`Machine.java:45-48`).
- **Flyway migration** — a new migration adds the `name` column to `machine` **and**
  `machine_aud`, plus the `uq_machine_owner_name` constraint on `machine`. Since
  existing rows have no name and the column is `NOT NULL`, the migration backfills
  existing rows first (e.g. set `name = host` as a one-time seed) and then adds the
  `NOT NULL` + unique constraint. (`machine_aud` columns stay nullable, matching the
  existing `_aud` pattern in `V3__machine.sql:56-72`.)
  - **Migration version — assign the NEXT FREE `Vn` at BUILD time.** Do **not**
    hardcode a number in this spec. The current tip is `V8__machine_facts_probed_at.sql`,
    but the concurrent monitoring branch (specs 021–026) may also add a migration
    (`V9__recipe_unique_per_machine.sql` already exists on that branch's worktrees),
    so the actual free `Vn` is only known when this branch is built and rebased onto
    the then-current `main`. Pick the lowest unused `Vn` at that moment.

### Service

- **`RegisterMachineInput`** (`MachineService.java:40-41`) gains a `name` field:
  `record RegisterMachineInput(String name, String host, int port, String loginUser)`.
- **`MachineService.register`** (`MachineService.java:67-97`) validates `name` is
  present (non-blank, trimmed) alongside the existing host/port/loginUser checks, and
  **pre-checks per-owner name uniqueness** — mirror the existing
  `existsByOwnerIdAndHostAndPortAndLoginUser` pre-check (`MachineService.java:86-89`)
  with a new `MachineRepository.existsByOwnerIdAndName(ownerId, name)` so a duplicate
  name returns a clean 409, not a raw constraint-violation 500. (Reuse the
  `MachineAlreadyRegisteredException` shape, or add a sibling for the name clash —
  the exception mapper in `common/MachineAlreadyRegisteredExceptionMapper.java`
  already maps that family to 409.) Set `machine.setName(trimmedName)` before save.

### MCP-facing view + tools

- **`MachineDtos.McpMachineView`** — a new record in `MachineDtos`
  (`machine/api/MachineDtos.java`) = `(String id, String name, MachineStatus status,
  List<String> tags)` with a static `of(Machine)` that sorts tag names exactly as
  `MachineView.of` does (`MachineDtos.java:34-41`). It deliberately has **no**
  host/port/loginUser accessor, so the omission is structural, not a serialization
  filter that could regress.
- **`MachineView`** (the UI view) gains `name`:
  `(String id, String name, String host, int port, String loginUser, MachineStatus status, List<String> tags)`.
- **`ListMachinesTool`** — replace the ad-hoc `summarize` map
  (`ListMachinesTool.java:89-96`) with `McpMachineView.of(machine)` and serialize
  that. Output per machine is `id/name/status/tags` only.
- **`RegisterMachineTool`** — (a) input schema
  (`RegisterMachineTool.java:27-37`) gains a **required** `name` string property
  (`required: ["name", "host", "loginUser"]`); still accepts `host`/`port`/`loginUser`.
  (b) It reads `name` from arguments, passes it into `RegisterMachineInput`, and (c)
  its **response** switches from the current host-echoing map
  (`RegisterMachineTool.java:63-70`) to `McpMachineView.of(machine)` — `id`, `name`,
  `status`, `tags`, **no host/port/loginUser**.
- **`TagMachineTool`** — no functional change (already `machineId` in, `id`+`tags`
  out). Optionally include `name` in its response for symmetry; not required.

### UI (REST + shell)

- **`MachineRS`** keeps the **full** `MachineView` on every method
  (`MachineRS.java:47-98`) — the human session still sees host/port/loginUser. Its
  `RegisterMachineRequest` (`MachineDtos.java:24`) gains a required `name` field, and
  `MachineRS.register` (`MachineRS.java:46-54`) passes it into `RegisterMachineInput`.
- **Register-machine form** gains a `name` input (required); the machines list and
  detail views render `name` prominently as the machine's title, with `host` still
  visible to the human beneath it. (Static UI shell + design system per spec-012;
  textContent-only rendering.)

### Tests to specify

- **`McpToolsWebTest`** (`src/test/java/com/iskeru/computeadmin/mcp/McpToolsWebTest.java`):
  - `list_machines` output for a machine with a known host/port/loginUser contains
    **no** `host`, `port`, or `loginUser` key — and **does** contain `id`, `name`,
    `status`, `tags`.
  - `register_machine` response contains **no** `host`/`port`/`loginUser` — and does
    contain `id`, `name`, `status`.
  - `register_machine` **rejects** a call missing `name` (schema-required).
- **`MachineServiceTest`** (`src/test/java/com/iskeru/computeadmin/machine/MachineServiceTest.java`):
  - `name` is required at registration (blank/null → 400).
  - per-owner name uniqueness — same owner + same name → 409; **different** owners may
    reuse the same name (mirrors the existing Tag/host uniqueness tests).
- **View divergence** (unit over `MachineDtos`, or `MachineWebTest` for the UI side):
  `McpMachineView.of` exposes only `id/name/status/tags`; `MachineView.of` still
  exposes host/port/loginUser — asserting the two views genuinely diverge, so a future
  refactor can't silently collapse them back into one leaky view.

## Known Gaps

- **`register_machine` still takes `host` as input — by design.** The agent must give
  an address to register a machine; it is providing a value it already possesses, not
  receiving one. Only the *return path* is closed. This is decision 3, not an
  oversight.
- **The UI intentionally still shows `host`/`port`/`loginUser` to the human.** The
  hardening is specifically about the **MCP → LLM** path. An authenticated human in
  their own browser session is not the exposure we're closing; they need the address
  to manage the machine.
- **Name-uniqueness scope is per-owner, not global.** Matches `Tag` and the existing
  `(owner, host, port, login_user)` key. Two different users may both name a machine
  `web-prod-1`; a single user may not. No global namespace, no rename API in this
  spec (rename can come later; the field is mutable on the model but no MCP/UI rename
  path is specified here).
- **Existing rows get a seeded name at migration time** (e.g. `name = host`), which
  re-introduces the host string as the *initial* name for pre-028 machines. That is a
  one-time backfill of the owner's own data into a field they can later edit; it does
  not re-open the MCP leak (the value is a name the owner chose to accept, not raw
  infra echoed as coordinates), but it's worth noting the seed derives from host.
- **No structural guarantee at the transport layer** that a future tool won't add a
  host field back — the protection is the separate `McpMachineView` type plus the
  view-divergence test. A lint/architecture test forbidding infra fields on MCP DTOs
  is a possible future hardening, not in scope here.
