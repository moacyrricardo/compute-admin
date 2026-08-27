# 060 — MCP surface audit

**Status:** todo · no branch yet · no Linear (blocked for this repo; tracked as `spec-060`).

## Context

This is the **capstone** of the 055–060 epic that graduates concern
[054](054-concern-lightweight-app-model.md) (the lightweight {app-script, script-folder,
context} model) and reconciles it with the surviving **verb & command** half of concern
[053](053-concern-app-model-and-verbs.md). Specs 055–059 add the record and resolution
seam (055), the non-listening/discovery sweeps (056), the per-context probing and its
`computeOther` integration (057), standalone-DB sizing (058), and the UI (059). Once those
land, the identity 054 derives (a path-collapsed context) and the verbs 053 speaks meet on
exactly one surface — the **MCP tool layer** (`src/main/java/com/iskeru/computeadmin/mcp/`)
— and that meeting must not weaken the core invariant. This spec is the **end-to-end audit
and reconciliation** of that surface: it defines checks and any renames/removals, not a pile
of new tools.

It sits **last** in the epic and **depends on all of 055–058**: 055 for the record shape,
the D1 symlink key, and the new S9 runtime fix + arch test (028's deferred hardening); 056/057
for the server-side sweeps and probes that feed `discover_recipes`; 058 for the standalone-DB
size checks, whose LITERAL argv carries absolute config/datadir paths
(`--defaults-extra-file=/etc/mysql/debian.cnf`, the engine datadir) this audit must confirm are
withheld from the MCP surface. It reconciles three things that
today live in separate places — the gate structural tests (`GateArchTest`,
`BlueprintGateTest`), the S9 path-hiding rule (`McpMachineView`, now generalized by 055), and
the reserved-param seam in `ActionService` where 054's accepted-basename identity and 053's
hashed verb both attach. The mcp-inventory audit found **no new MCP tool is wired for 053's
F1 verbs yet** — F1 is a decision, not code — so this spec's job is to *assert the invariants
that will govern such a tool when it lands* and to *close the residual leaks that already
exist* (the `ListActionsTool` argToken path echo), not to build the verb tools.

## Decision

The following are decided (drawn from 054's locked decisions and 053's surviving verb
contract), stated prescriptively for the audit.

1. **The gate stays singular and untouched.** `RunActionTool` → `RunService.run` remains the
   **sole** gate entry point on the MCP surface. No tool added or changed by 055–059 becomes
   an approve path or a second run path. `GateArchTest`'s four assertions
   (`mcpSourcesExist`, `noMcpClassReferencesApprovalService`, `noMcpClassReferencesARepository`,
   `noMcpToolIsAnApproveTool`) and `BlueprintGateTest`'s extension stay **green as shipped**.
   The `mcp` package holds no business rules (ARCH.md gate point 2; CONTRIBUTING §7).

2. **S9 holds across the *whole* tool surface, not just machine coordinates.** 054 D1's
   context key exists in two representations that are **never conflated**: an **internal**
   dedup/pin key (resolved-physical / release-promoted-logical path — an absolute filesystem
   path, treated as S9-secret) and an **external** MCP identity (a **basename-derived,
   human-accepted label** per 053 dec. 12). No MCP tool response serializes the internal key
   — no absolute-path LITERAL argToken value, no `context`/`appRoot`/`scriptFolder` field,
   no raw path anywhere. This generalizes the protection that today is only the hand-built
   `McpMachineView` (S9, resolved by spec-028 for `host`/`port`/`loginUser`) to the **argv /
   context surface**. 055 introduces the runtime fix (withholding path-shaped LITERAL values in
   `ListActionsTool.tokenView`) and the source-scan regression that together enforce it; **060 is
   the sweep that verifies every tool and resource passes** and that the pre-existing
   `ListActionsTool` argToken path echo is closed.

3. **The identity↔verb seam is enforced by *which container* each declaration lands in.** At
   action-creation time (`ActionService.addAction`, reached over MCP via `AddActionTool`,
   `AddBlueprintActionTool`, and the `DiscoverRecipesTool` proposal path):
   - 054's derived app identity arrives as the **accepted value** of the reserved `app-name`
     param (basename only; the raw path never crossed MCP).
   - 053's **verb** rides as a **sibling reserved `paramDef`** that enters `ActionSnapshot.hash`
     — changing a verb forces re-approval (053 dec. 7).
   - 053's `role`/`dedication` ride as **A4 un-audited side-data** on the recipe's
     `app_port_list` JSON, **outside** the hash — reclassification never interrupts an operator.
   - **Never-in-argv (053 dec. 6):** no declaration param name (`app-name`, `verb`, `part`)
     appears as a `PARAM` argToken value. This is a *new* invariant, not inherited from spec-026.
   The audit verifies this split *by construction* (verb in a hashed `paramDef`; role/dedication
   in un-hashed side-data), not by runtime behavior.

4. **The pin/hash key matches the S9-safe representation.** The argv/pin LITERAL that enters
   `ActionSnapshot.hash` is the **redeploy-stable logical/promoted** path (055 rider adopting
   054 D1), so a symlink flip does not spuriously force re-approval; the **physical** path
   drives only the spec-015 `approvedScriptHash` sibling check, which stays **out** of
   `ActionSnapshot.hash` (015's pure-offline-hash separation). The audit confirms neither
   representation is emitted over MCP (feeds back into decision 2).

5. **The curated catalog stays in lockstep with the bean set.** `McpCatalogRS`
   (`GET /api/mcp/tools`) and its pin `McpCatalogWebTest` reflect the exact registered tool
   set; no drift. Any 053-F1 verb tool, when built, must be added here and under `GateArchTest`.

6. **This spec builds no new tools.** 053's F1 verb-level MCP tools are a *decision, not code*;
   their build is a separate 053-graduation spec. 060 asserts the invariants (1–5) that govern
   them and performs any **reconciliation delta** (rename/removal) that 055–059 make necessary
   on the existing surface. Expected delta after 055–059: **the surface is unchanged**, the
   `ListActionsTool` path echo is closed by 055's runtime fix (regression-guarded by its arch
   test), and this audit is the green-light.

## Implementation

Deliverable is an **audit matrix backed by tests**, plus any reconciliation edits it turns up.
No new migration; no new MCP tool; no gate change.

### A. Gate-integrity checklist (assert, don't rebuild)

Confirm, and keep green, the structural guarantees the inventory catalogued:

- `src/test/java/com/iskeru/computeadmin/recipe/GateArchTest.java` — its four tests still scan
  the whole `mcp` package (no `ApprovalService`, no `*Repository`, no tool name containing
  `approve`/`approval`). If 056/057 added any `@Component` under `mcp/`, it falls under this
  scan unchanged.
- `src/test/java/com/iskeru/computeadmin/blueprint/BlueprintGateTest.java` — same guarantee for
  the blueprint tools.
- `RunActionTool` remains the only tool calling `RunService.run`; create/propose tools
  (`AddActionTool`, `AddRecipeTool`, `AddBlueprintTool`, `AddBlueprintActionTool`,
  `InstantiateBlueprintTool`, `DiscoverRecipesTool`) still land `DRAFT` / `PENDING_APPROVAL`
  only; `ListActionsTool` still stamps `pending_approval: true` on non-`APPROVED` actions
  (ARCH gate point 4) and never approves.
- Auth boundary: `requiresAuth()` returns `false` **only** on `BeginSetupTool` /
  `CompleteSetupTool`; every other tool (and any future verb tool) requires auth via the
  `McpTokenAuthFilter` / `McpServletConfig` wrapper.

### B. S9 path-leak sweep (the core new work of this spec)

055 splits 028's deferred hardening (ARCH.md S9 residue noted at spec-028) into a **runtime fix**
— `ListActionsTool.tokenView` withholds / basename-renders any path-shaped LITERAL argToken value,
so no absolute path is emitted at run time — and a **source-scan regression test**, same style as
`GateArchTest`, that bans re-introducing a serializer which echoes a raw `argToken` `getValue()`
or exposes a `context`/`appRoot`/`scriptFolder`/path field on any serialized view. The scan cannot
prove the runtime-output property (the leaking value is DB data written at run time, not a source
literal); the runtime fix does. 060 verifies the sweep is **complete** by enumerating every surface
bean and its authoring-time path exposure:

| Bean | Path exposure today | Required post-055 state |
|---|---|---|
| `ListActionsTool` | **leaks** — `summarize`/`tokenView` echo raw `argToken` value; a CUSTOM action's leading LITERAL is the absolute script path | argToken values that are absolute paths are withheld / rendered as the accepted basename identity by the 055 runtime fix; regression scan forbids re-adding the raw echo |
| `DatabaseDiscoverer` DB-size actions (058), via `ListActionsTool` | **leaks** — the standalone size checks' LITERAL argv carries `--defaults-extra-file=/etc/mysql/debian.cnf` and the engine datadir (absolute paths) that `ListActionsTool` would echo | those path-shaped LITERAL values are withheld / rendered as the engine basename identity ("postgresql"/"mysql"/"mariadb") by the same 055 runtime fix; regression scan forbids re-adding the raw echo |
| `RegisterMachineTool`, `ListMachinesTool`, `TagMachineTool` | clean — `McpMachineView` only (id/name/status/tags) | unchanged |
| `ListRecipesTool`, `ListBlueprintsTool` | clean (id/machineId/name/type) | unchanged |
| `DiscoverRecipesTool` | proposal summaries must not surface a resolved `context`/`scriptFolder` path from 055/056 | proposals surface **accepted basename** identity only, never the 054 physical/logical key |
| `AddActionTool`, `AddBlueprintActionTool` | accept `argTokens[]`/`paramDefs[]` as input; must not *echo* an absolute-path LITERAL back | input accepted; response carries no raw path |
| `GetRunTool`, `RunActionTool`, `RunOutputResource` | run **output** (stdout/stderr) may contain paths — that is run output, not authoring identity | unchanged (out of the identity ban's scope; flagged as such) |
| `PublicKeyResource` | public key half only (S2) | unchanged |
| `InstantiateBlueprintTool`, `AddRecipeTool`, `AddBlueprintTool`, `BeginSetupTool`, `CompleteSetupTool` | clean of authoring paths | unchanged |

The sweep's structural backing is two-part: the 055 **runtime fix** in `ListActionsTool.tokenView`
that withholds path-shaped LITERAL values (closing the only live hole, including 058's
`debian.cnf`/datadir literals), plus the 055 **source-scan regression** written in `GateArchTest`'s
style (walk `mcp/` sources, ban a serializer that echoes a raw `argToken` `getValue()` or exposes a
path field). 060 confirms `ListActionsTool` is the only pre-existing hole and that the runtime fix
closes it; the remaining rows are regression assertions the scan backs.

### C. Identity↔verb seam verification

The seam is the reserved-param branch in
`src/main/java/com/iskeru/computeadmin/recipe/service/ActionService.java` (the `reservedAppName`
validation, ~L326–349, where `app-name` must be an `ALLOWED_SET` matching
`ParamBinder.APP_NAME_PATTERN`). Verify **by construction**:

- **Verb enters the hash.** `ActionSnapshot.canonical()` today hashes `(sudo, argTokens,
  paramDefs)` and has no `app-name`/`verb`/`part` field of its own; the `app-name` `ALLOWED_SET`
  is hashed *via* its `paramDef` `allowed=` values (spec-036 note). So a verb "inside the hash"
  (053 dec. 5 A1 half, dec. 7) must be modeled as a **hashed `paramDef`**, not a free field —
  assert a verb declaration lands in `paramDefs` and thus in `ActionSnapshot.hash`.
- **Role/dedication stay outside the hash.** Assert `role`/`dedication` ride as A4 side-data on
  the `app_port_list` JSON (the un-audited channel `DiscoveryService.persist` /
  `refreshDiscoveredAppPortList` writes, 055's home for `contextKey`/`contextDisplay`), never as
  a `paramDef` — so reclassification does not re-approve.
- **Never-in-argv.** Assert (at action creation, across `AddActionTool`, `AddBlueprintActionTool`,
  and discovery-proposed actions) that no declaration param name appears as a `PARAM` argToken
  value — the 053 dec. 6 invariant. This is new enforcement, not inherited from spec-026's
  `{app-name}`-into-`systemctl` binding.

### D. Catalog sync

`src/main/java/com/iskeru/computeadmin/mcp/api/McpCatalogRS.java` (`GET /api/mcp/tools`,
`@Secured`) re-states the surface for the UI's MCP screen, grouped Read / Create / Run /
Bootstrap; `McpCatalogWebTest` pins it to the registered bean names. Confirm no drift after
055–059 and record that any 053-F1 verb tool must update both.

### E. Reconciliation delta

The audit's *output* is the delta. Based on the inventory, the expected delta after 055–057 is:

- **No new tool, no removed tool** — 055 is side-data + an arch test; 056's sweeps are
  server-side `RecipeDiscoverer` impls reached through the existing `DiscoverRecipesTool`; 057's
  probing is client-side (`app.js` `computeOther`, spec-041 integration point) plus a read-only
  server aggregate (`MonitorService`) — none add an MCP verb-tool.
- **One residual fix folded in from 055:** the `ListActionsTool` argToken path echo is closed by
  055's runtime `tokenView` fix (regression-guarded by its source-scan arch test); 060 verifies it
  and adds the regression row.
- If the audit finds any tool emitting a 054 path key or any declaration param bound into argv,
  **that is a bug to fix here** (rename the field to the accepted basename, move the value to
  side-data, or withhold it) — enumerated as concrete edits in the audit run, not pre-listed.

No `pom.xml` version bump (project policy: unversioned).

## Known Gaps

- **053 F1 verb-level MCP tools are not built here.** MCP-speaks-verbs is a decision (053 dec.
  14); wiring verb tools (`start·stop·restart·deploy·status·logs·metrics`) as MCP capabilities is
  a separate **053-graduation** spec. 060 fixes the invariants those tools must satisfy (gate
  singularity, the hashed-verb/side-data-role split, never-in-argv, S9 basename-only identity,
  catalog + `GateArchTest` inclusion) so that build is a mechanical fit, not a re-litigation.
- **`part` addressing stays UI-only** (053 dec. 15) — not MCP-addressable in v1; out of this
  audit's surface.
- **OQ5 (053) — destructive-verb friction** (the most dangerous out-of-vocabulary script gets the
  least friction) is **noted, not resolved**: out-of-vocabulary scripts remain plain run chips and
  this audit does not add a destructive declaration.
- **The UI surface** is 059's and the **visual-identity reskin** is explicitly deferred by 059 to
  a separate concern — neither touches the MCP surface, and both are audited here only to confirm
  they add no tool. **Standalone (non-docker) DB sizing (058) does** touch the surface: it adds no
  tool, but its size checks put path-shaped LITERAL argTokens
  (`--defaults-extra-file=/etc/mysql/debian.cnf`, the engine datadir) on the values `ListActionsTool`
  echoes, so it is audited in §B like every other path-emitting bean, not merely confirmed
  tool-free.
- **Transport hardening (S1')** — non-loopback bind, TLS, CSRF/Origin on `/api` — stays deferred;
  this audit covers the *tool-response* surface and the auth boundary, not transport.
- **Run output paths.** `get_run` / `RunOutputResource` stdout/stderr may contain absolute paths;
  that is captured run *output*, not authoring identity, and is intentionally outside the S9
  identity-ban (flagged in the sweep table, not fixed).
