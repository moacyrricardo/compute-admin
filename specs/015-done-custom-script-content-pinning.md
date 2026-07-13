# 015 — Custom-script content-pinning

> **Status: done.** Branch `moacyrricardo/spec-015-custom-script-content-pinning`.
>
> Resolves **H5** (specs/catalog.md deferred-hardening backlog) and hardens the
> **S5** posture (ARCH.md security register). Security spec sitting beside the
> S-register: it closes a TOCTOU hole in the approve-then-run invariant for
> `CUSTOM` script actions.

## How the implementation diverged from the spec

- **Migration number:** shipped as `V12__custom_script_content_pinning.sql`, not
  the `V8` the spec text names (`V8` was already taken — it is
  `V8__machine_facts_probed_at.sql`). Same content: nullable `approved_script_hash`
  on `action` and `action_aud`.
- **`ScriptPinService` chosen over a private method on `ApprovalService`** (the spec
  offered either). It exposes `probe(machine, scriptPath, sudo)` plus a static
  `scriptPath(action)` (leading `LITERAL` token by position), shared by
  `ApprovalService` and `RunService`, isolating the SSH concern.
- **Probe ordering at approval:** the probe runs *before* any state mutation, so an
  unreadable script refuses approval with the action left untouched
  (`PENDING_APPROVAL`) rather than mutated-then-rolled-back — cleaner and robust to
  the test persistence context.
- **`ScriptUnreadableException` maps to 409** (the spec's recommended choice over 400):
  the target's state prevents pinning.
- **Run-time probe stays inside `RunService.run`'s `@Transactional` scope.** The spec's
  Implementation section prescribes the check inline (right after the
  `ActionModifiedException` gate) and that is what shipped, matching the existing
  spec-004 structural gate. The Known Gap "SSH inside the run transaction" (mirror
  spec-013's out-of-transaction probe) was **not** closed here — it remains a
  documented Known Gap; extracting the probe would have required restructuring the
  carefully-built fan-out/cancel/after-commit transaction semantics for a hygiene gain
  the spec itself files as deferred.
- **Test wiring:** because `ApprovalService`/`RunService` now transitively depend on the
  `SshExecutor` port via `ScriptPinService`, slice tests that import them gained a
  test `StubSshExecutor` (or their existing fake now answers `sha256sum`).

## Context

The core invariant (ARCH.md) is *approve-then-run*: an action runs only while
`APPROVED`, and the run path re-verifies that the live definition still matches
what was approved. That binding is a SHA-256 **content hash** captured at approval
(`ApprovalService.approve`, `ApprovalService.java:65`) and re-checked before every
run (`RunService.run`, `RunService.java:112` → `ActionModifiedException` 409). Any
structural edit resets the action to `DRAFT` and clears the hash
(`ActionService.editAction`, `ActionService.java:190-196`), so the definition can
never be approved-benign-then-mutated.

**The hash does not cover the one thing a `CUSTOM` action actually executes: the
script's bytes.**

- `ActionSnapshot.hash` (`ActionSnapshot.java:36-73`) is a pure function of the
  entity — it digests `sudo` + the ordered `argTokens` + the sorted `paramDefs`
  (`ActionSnapshot.java:48-72`). It never reads any file.
- A `CUSTOM` action wraps an on-box script by storing its **absolute path** as the
  leading `LITERAL` argv token (`ActionService.addCustomAction`,
  `ActionService.java:152-154`). Authoring validates only that the path is
  non-blank and absolute (`ActionService.java:121-128`) — never its contents.
- At run time `ParamBinder.bind` emits that literal path verbatim as `argv[0]`
  (`ParamBinder.java:54-56`), and `MinaSshExecutor.assembleCommand` single-quotes
  it into the one shell line SSH `exec` runs (`MinaSshExecutor.java:147-159`).

**The TOCTOU hole.** An action approved against, say, `/home/app/run.sh` binds a
hash over `(sudo, [LITERAL /home/app/run.sh, …params], paramDefs)`. Whoever can
write those bytes *after* approval changes **what executes** without changing the
hash — the structural-drift gate (`ActionModifiedException`) sees nothing, because
the token value (`/home/app/run.sh`) is unchanged. The app re-executes whatever
the path *becomes* at run time. This defeats the app's reason to exist.

**Escalation (S5).** `sudo` prefixes the command with `sudo -n`
(`MinaSshExecutor.java:149-151`, S5 passwordless posture). If a `sudo` CUSTOM
action points at a path a *lesser-privileged* target-local user can write, that
user gains privileged execution through an already-approved action — a
privilege-escalation vector, not just a correctness gap. This is exactly the Known
Gap that spec-007 flagged and deferred (`specs/007-done-custom-command-recipes.md`,
"Known Gaps" and "Deferred to a future (new-arch) spec").

**Scope.** The risk surface is the operator's **own writable scripts** — i.e.
actions under a `RecipeType.CUSTOM` recipe (`RecipeType.java:15`). Discovery-proposed
and blueprint-instantiated actions are analyzed under *Impact / blast radius* below.

## Options considered

### Option A — Pin the path's bytes: hash-at-approval, re-hash-at-run

Capture a SHA-256 of the script's **current bytes** at approval time by probing the
target over the existing `SshExecutor` port (`sha256sum <path>`), store it beside
`approvedSnapshotHash`, and re-probe + compare before each run — refusing with a new
typed `ScriptModifiedException` (409) that **mirrors `ActionModifiedException`
exactly** (`ActionModifiedException.java`, `ActionModifiedExceptionMapper.java`).

- **Integrity:** closes the approval→run TOCTOU window for the wrapped file. The
  bytes that run are the bytes that were on the box when a human approved.
- **Execution model unchanged:** we still run the operator's *own* file, in place.
  No upload, no staging, no new SSH capability.
- **Blast radius:** small and localized — one nullable column (+ Flyway `V8`),
  `ApprovalService` gains an `SshExecutor` dependency, `RunService` gains one
  re-probe, one new exception + mapper. It is the *same shape* as the existing
  spec-004/005 snapshot gate, so it reuses a proven pattern.
- **Costs:** approval now requires the box to be **reachable** (a pure DB
  transition becomes a DB transition + one SSH round-trip); run adds one SSH
  round-trip before dispatch. Integrity is "same bytes as at approval" — it does
  **not** vet what the script *does*, nor bytes it pulls in at run time.

### Option B — Stage the reviewed copy: capture-and-store content, execute the stored copy

At approval, read the full script content over SSH, store it in the DB, and at run
time **upload that stored copy** to the target (a staging dir) and execute the
staged file instead of the in-place path.

- **Integrity:** strongest in principle — execution is byte-identical to the stored
  copy regardless of any target-side mutation of the original path.
- **Costs / blast radius: large.** It changes the execution model:
  - the `SshExecutor` port (`SshExecutor.java`) must grow an **upload/stage**
    capability (SFTP/scp via MINA) — a new adapter surface both `MinaSshExecutor`
    **and** `LocalDevSshExecutor` must implement and keep injection-safe;
  - `RunService.execute` must stage-then-exec, with a staging directory, unique
    per-run naming, permission bits (exec), and cleanup on completion/failure;
  - staging into privileged locations reintroduces sudo questions of its own;
  - the DB now stores arbitrary script blobs (size limits, secrets-in-scripts).
  - It also breaks the product's stated model — spec-007: *"wraps an existing
    script already on the box"* — and the integrity is partly illusory anyway: a
    reviewer approves against `description`, and the script can still `source`,
    `curl`, or exec other on-box files at run time, which staging does not pin.

## Decision — Option A (recommended)

**Pin the path's current bytes at approval and re-verify before each run**, keyed
to `CUSTOM` recipe actions. Store the script digest as a **sibling nullable column**
on `Action` (not folded into `ActionSnapshot`), and enforce it as a **second,
independent run-time gate** next to the existing structural-drift gate.

**Why A over B.** For a single **local** instance (ARCH.md posture), A closes the
actual reported hole — post-approval byte-swap of the operator's writable script —
with a blast radius that mirrors the existing, trusted spec-004/005 gate. B triples
the execution model (a new SSH capability, staging, cleanup, blob storage) for an
integrity gain that is partial in practice and fights the "run the operator's own
file in place" model. B can be revisited if compute-admin ever executes scripts on
untrusted-tenant hosts; today it is over-engineering.

**Why a sibling column, not folding into `ActionSnapshot`.** `ActionSnapshot.hash`
is deliberately a **pure, offline** function of the persisted entity
(`ActionSnapshot.java:36`) — it has no SSH context and is computed on both the
approve and run paths without touching the network. Folding a live-probed script
digest into it would force the run-time `ActionSnapshot.hash(action)` re-computation
(`RunService.java:112`) to first re-probe the box and mutate the entity before
hashing — coupling a pure hash to SSH and conflating two distinct concerns:
*structural definition drift* (always checkable, offline) vs *script content drift*
(requires the box, only for pinned actions). Keeping `approvedScriptHash` a separate
column preserves that separation and keeps each gate independently testable.

## Implementation

All new code cites `spec-015` in a Javadoc line, per ARCH.md conventions.

### Model + migration (travel in the same commit)

- **`Action`** (`recipe/model/Action.java`): add
  ```java
  /** SHA-256 of the pinned script's bytes at approval (CUSTOM actions); null otherwise. spec-015. */
  @Column(name = "approved_script_hash", length = 64)
  private String approvedScriptHash;
  ```
  It is `@Audited` by inheritance (the class is `@Audited`), so it lands in
  `action_aud` — add the matching column there too.
- **Flyway `V8__custom_script_content_pinning.sql`** (H2 dialect, opening comment
  names spec-015): `ALTER TABLE action ADD COLUMN approved_script_hash VARCHAR(64);`
  and `ALTER TABLE action_aud ADD COLUMN approved_script_hash VARCHAR(64);`. Nullable
  and back-fill-free: existing rows read `null` = "not pinned", so already-approved
  actions keep working until re-approved (see Known Gaps).

### Capturing the hash at approval

- New helper `ScriptPinService` (`recipe/service`, `@Component`) — or a private
  method on `ApprovalService`; a dedicated helper is preferred so the SSH concern
  is isolated and unit-testable. It exposes:
  ```java
  /** SHA-256 hex of the script at `scriptPath` on `machine`, probed over SSH. spec-015. */
  Optional<String> probe(Machine machine, String scriptPath, boolean sudo);
  ```
  - Build `argv = ["sha256sum", scriptPath]`, `SshTarget` from
    `machine.getHost()/getPort()/getLoginUser()`, and call
    `SshExecutor.exec(target, argv, sudo)` — reusing the POSIX single-quoting in
    `MinaSshExecutor.assembleCommand` so a path with **spaces** stays one argument.
  - On `ExecResult.succeeded()`, parse the leading 64-hex token of stdout
    (`sha256sum` prints `<hex>␠␠<path>`). Non-zero exit or unparseable output ⇒ the
    script is unreadable / `sha256sum` is absent ⇒ **refuse approval** with a typed
    `ScriptUnreadableException` (400/409 — see below), never a silent skip.
- **`ApprovalService.approve`** (`ApprovalService.java:58-69`): after setting
  `APPROVED` and `approvedSnapshotHash`, if the action is a **`CUSTOM`** action
  (`action.getRecipe().getType() == RecipeType.CUSTOM`), resolve its script path (the
  leading `LITERAL` token — `argTokens` sorted by position, first element), probe it,
  and set `approvedScriptHash`. Inject `SshExecutor` (or `ScriptPinService`) into
  `ApprovalService`. Non-`CUSTOM` actions leave `approvedScriptHash` null.
  - Use the action's own `sudo` flag for the probe so a root-readable-only script is
    still hashable when the action already escalates (reuses the S5 posture; no new
    privilege).
- **`ApprovalService.revoke`** (`ApprovalService.java:71-84`) and
  **`ActionService.editAction`** (`ActionService.java:190-196`): also null out
  `approvedScriptHash` wherever they already clear `approvedSnapshotHash`, so the two
  hashes are always cleared together.

### Re-verifying at run time (mirror the existing gate exactly)

- **`RunService.run`** (`RunService.java:98-136`): immediately **after** the existing
  `ActionModifiedException` check (`RunService.java:112`), add the content gate for
  pinned actions:
  ```java
  if (action.getApprovedScriptHash() != null) {          // pinned CUSTOM action
      String live = scriptPin.probe(machine, scriptPath(action), action.isSudo())
              .orElseThrow(() -> new ScriptModifiedException(actionId));
      if (!live.equals(action.getApprovedScriptHash())) {
          throw new ScriptModifiedException(actionId);
      }
  }
  ```
  Thrown **synchronously**, before the `QUEUED` run is persisted and dispatched, so
  the HTTP caller gets a **409** — exactly as `ActionModifiedException` does today.
  A drifted/missing/unreadable script never runs.
- New **`ScriptModifiedException`** (`run/service`, `extends RuntimeException`,
  one-line Javadoc → HTTP 409), a verbatim analogue of `ActionModifiedException.java`,
  plus **`ScriptModifiedExceptionMapper`** (`common/`, `@Provider @Component`)
  returning `Map.of("error", "script_modified")` — mirroring
  `ActionModifiedExceptionMapper.java`.
- New **`ScriptUnreadableException`** (`recipe/service`) for the *approval-time*
  failure (script missing / `sha256sum` absent / needs sudo it doesn't have), mapped
  in `common/` to `Map.of("error", "script_unreadable")`. Decide 409 vs 400: 409
  (Conflict) reads best — the target's state prevents pinning.

### Tests (JUnit 5 + AssertJ, module-level package, per ARCH.md)

- **`ApprovalServiceTest`** (`…recipe`), with a `@TestConfiguration` `@Bean @Primary`
  fake `SshExecutor`:
  - `approve_CustomAction_PinsScriptHash` — fake returns a known `sha256sum` line;
    assert `approvedScriptHash` is the parsed hex.
  - `approve_NonCustomAction_LeavesScriptHashNull`.
  - `approve_ScriptUnreadable_Refuses` — fake returns non-zero exit ⇒
    `ScriptUnreadableException`, action stays `PENDING_APPROVAL`.
  - `approve_PathWithSpaces_ProbesSingleArgument` — assert the fake received argv
    `["sha256sum", "/path with space/run.sh"]` (quoting is the adapter's job).
- **`RunServiceTest`** (`…run`): `run_ScriptContentDrifted_Throws409` (fake returns a
  different hex than the pinned one ⇒ `ScriptModifiedException`);
  `run_ScriptUnchanged_Runs`; `run_NonPinnedAction_SkipsProbe` (no extra SSH call).
- **`ActionSnapshotTest`**: assert `approvedScriptHash` does **not** feed
  `ActionSnapshot.hash` (structural hash unchanged by pinning) — the two gates stay
  independent.
- **`MinaSshExecutorTest`**: `assembleCommand(["sha256sum", "/a b/run.sh"], false)`
  single-quotes the path (already covered generically; add the probe case).
- **Offline verification** uses the `localssh` profile (`LocalDevSshExecutor`), which
  runs `sha256sum` as a local process — no container needed.

## Impact / blast radius

- **`recipe` module (core).** `Action` + `V8` migration; `ApprovalService` gains an
  SSH dependency (previously pure DB); `editAction`/`revoke` clear the new column.
  This is the only invariant-bearing change.
- **`run` module.** One added re-probe in `RunService.run`, new
  `ScriptModifiedException` + mapper. The existing `Run.approvedSnapshotHash` audit
  field is untouched; optionally also record `approvedScriptHash` on `Run` for the
  execution log (nice-to-have, not required to close H5).
- **MCP surface — no change, invariant preserved.** There is **no**
  `add_custom_action` MCP tool (only `AddActionTool` with raw `argTokens`;
  `AddRecipeTool`/`AddBlueprintTool` merely mention `CUSTOM` as a type string). All
  pinning lives in `ApprovalService`, which the `mcp` module already may not reference
  (`GateArchTest.noMcpClassReferencesApprovalService`). So content-pinning adds **no**
  MCP tool, touches **no** MCP class, and stays inside the UI-only-approval invariant
  — `GateArchTest` continues to pass unchanged. An agent can still `add_action` a
  path-bearing LITERAL, but it lands `DRAFT` and only a UI approval pins + gates it.
- **Discovery (`discovery/…`).** Discoverers propose built-in service recipes
  (`NGINX/DOCKER/DATABASE/CRON`), persisted `PENDING_APPROVAL`
  (`ProposedAction.java`, `DiscoveryService`). Keying pinning on
  `RecipeType.CUSTOM` means discovered actions are **not** pinned even when a proposed
  argv leads with an absolute-path `LITERAL` to a system binary — see the non-custom
  discussion below. No discovery change.
- **Blueprints (`blueprint/…`).** `BlueprintAction` (`BlueprintAction.java`) carries
  the same argv shape but **no `scriptPath` concept and no machine** — it is a
  machine-independent template with no approval state, so there is nothing to probe at
  authoring time. `InstantiationService` copies tokens into a per-machine `Action`
  (`InstantiationService.java:191-196`); that instance is approved through the *same*
  `ApprovalService.approve`, so if the instantiated recipe is `CUSTOM` it is pinned
  there automatically. **No blueprint change.**
- **Approval reachability.** Approval of a `CUSTOM` action now requires the machine
  to be reachable (one SSH round-trip). Previously a pure DB transition. Acceptable
  for the security gain; documented as a Known Gap.
- **S5 posture.** Pinning **narrows** S5: the post-approval byte-swap vector is
  closed. It does **not** eliminate S5 — the sudoers-allowlist recommendation stands
  (below).

## Known Gaps

- **Approval requires reachability.** A `CUSTOM` action cannot be approved while its
  machine is offline (nothing to probe). No "pin lazily on first run" fallback — that
  would defeat the review-time guarantee. Operators approve when the box is up.
- **`sha256sum` must exist on the target** (coreutils; near-universal on Linux). If
  absent, approval is refused (`ScriptUnreadableException`) rather than silently
  unpinned. A future spec could fall back to `shasum -a 256` / `openssl dgst`.
- **Legacy approved actions read `approved_script_hash = null`** and therefore skip
  the content gate until re-approved. This is intentional (no back-fill probe of
  every box on migrate); an operator hardens an existing CUSTOM action by re-approving
  it. A follow-up could force-revoke pre-015 CUSTOM approvals to make pinning
  mandatory.
- **Pinning follows symlinks** (`sha256sum` hashes the link target's bytes). A swapped
  link target changes the hash and is caught; a swapped *file at the same inode* is
  caught; this is the intended behavior.
- **Integrity is byte-of-the-file, not behavior.** Pinning does not vet what the
  script does, nor bytes it reads/execs at run time (`source`, `curl | sh`, etc.).
  Reviewers still approve against `description`, which they must keep honest.
- **S5 stays as posture.** Content-pinning removes the *post-approval* escalation
  path; it does not replace a narrow per-command sudoers allowlist. If a lesser user
  can write the file *before* approval, the reviewer never sees the bytes (only
  `description`). ARCH.md S5's hardening trigger (scoped `NOPASSWD` allowlist per
  command) remains open.
- **SSH inside the run transaction.** The synchronous run-time re-probe adds a network
  call inside `RunService.run`'s `@Transactional` scope — the same anti-pattern
  spec-013 (H3) addresses for discovery. Mirror spec-013's fix: perform the probe
  *outside* the persistence transaction (before opening it, or via a non-transactional
  boundary) while still returning the 409 synchronously.
