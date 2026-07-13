# 017 — Transaction-boundary strategy

> **Concern (exploratory — options open, no decision yet).** Frames how we draw the
> "slow I/O outside the DB transaction, then persist in one short transaction"
> boundary, and weighs three mechanisms against the code as shipped. Linear is
> **blocked** for this repo, so this carries no issue id; commits use `spec-017`
> subjects. If this graduates to a decision it becomes a spec (and this file gets a
> WARNING pointing at it).

## Problem

Two paths need the same shape: run the **slow, network-bound** phase (SSH probes)
with **no DB transaction open**, then persist the results in **one short
transaction**. Holding a JPA transaction (and its pooled connection) open across
SSH round-trips is exactly the resource-hygiene smell spec-013 set out to remove
(H3 discovery, H6 connectivity; see [catalog.md](./catalog.md) backlog and
[013](./013-done-runtime-resource-hygiene.md)).

**How 013 solved it — programmatic `TransactionTemplate` (option A, as-built).**

- `discovery/service/DiscoveryService.java:87-96` — `discover(machineId)` resolves
  the machine, loops the discoverers to collect `ProposedRecipe`s in memory with no
  open tx, then persists them in `tx.execute(status -> persist(...))`. The template
  is built in the constructor from an injected `PlatformTransactionManager`
  (`DiscoveryService.java:53-66`). The Javadoc (`DiscoveryService.java:68-83`)
  spells out *why* a template and not a bare annotation: a self-called
  `@Transactional persist(...)` is a proxy no-op.
- `machine/job/ConnectivityCheckJob.java:71-107` — `checkAll()` snapshots the fleet
  into scalar `Probe`s, probes them off a bounded fixed pool
  (`ConnectivityCheckJob.java:81-90`), then for each result calls `apply(...)`, which
  runs `tx.executeWithoutResult(...)` **on the job thread** (`:97-107`). The template
  is likewise built from an injected PTM (`:62-69`). The re-load-and-compare inside
  the tx keeps "unchanged status ⇒ no `machine_aud` revision" (spec-003).

Both deliberately avoid annotating a private method because Spring's declarative
`@Transactional` is applied by a proxy that only intercepts **external** calls; a
`this.persist(...)` call bypasses the proxy, so no transaction starts and each
nested `RecipeService`/`ActionService`/`ApprovalService` write opens its own tiny
`REQUIRED` tx — no atomicity. The 013 spec named a **separate `@Transactional`
collaborator bean** as "the acceptable alternative"
([013](./013-done-runtime-resource-hygiene.md), H3 bullet, lines 118-122).

**The question.** Instead of injecting a PTM / `TransactionTemplate`, could we get
the same transactional result via **(B) bean refactoring** — extract the persist
block into a separate `@Transactional` collaborator bean, so ordinary declarative
`@Transactional` applies (external call → proxy intercepts → one tx)? Or via
**(C) `@Async` + `CompletableFuture`** — the persist runs in a separate proxied
method (so `@Transactional` is honored on the async proxy), returning a
`CompletableFuture` the caller `join`s? This concern investigates all three.

The declarative feature services are the contrast baseline: `RecipeService`
(`recipe/service/RecipeService.java:44`, `:68`, `:118`, `:140`), `ActionService`
(`:82`, `:116`, `:172`), `ApprovalService` (`:43`, `:58`, `:72`), `MachineService`
(`:51`, `:101`, `:124`) all use a plain method-level `@Transactional` on writes and
leave reads unannotated — the ARCH convention (`ARCH.md:226`,
`spring.jpa.open-in-view=false`). None of them needs a boundary *around a block of
other services' calls*; each is a single self-contained write. That is the shape
`@Transactional` fits, and the shape A/B/C are competing to serve where it does not.

There is also a real async precedent already in the tree — worth contrasting with C
(§2, §3): `run/service/RunService.java:251-262` `submitAfterCommit(...)` registers a
`TransactionSynchronization.afterCommit()` that dispatches `execute(...)` onto the
bounded `runExecutor` (`config/AsyncConfig.java:38-50`). Crucially that is
**fire-and-forget after commit** — the request thread does **not** join it, the run
row is already committed, and the worker path deliberately runs with **no bound
`CurrentUser`** (`RunService.java:191` comment). It is *not* a "persist in a short
tx that the caller waits for"; it is "dispatch the long side-effect once the short
tx has committed". That difference is the whole argument in §2–§3.

## Options / Hypotheses

### Option A — programmatic `TransactionTemplate` (as-built, spec-013)

Inject `PlatformTransactionManager`, wrap the persist block in
`tx.execute(...)` / `tx.executeWithoutResult(...)`. Shipped and green.

*Sketch (current code):*

```java
public List<DiscoveredRecipe> discover(String machineId) {
    Machine machine = machineService.requireMachine(machineId);
    List<ProposedRecipe> proposals = probeAll(machine);   // no tx open
    return tx.execute(status -> persist(machineId, proposals)); // one short tx
}
```

- **1. Proxy / self-invocation:** sidesteps it entirely — there is no annotation to
  intercept, the boundary is opened imperatively. The nested `@Transactional`
  service writes are `REQUIRED`, so they **join** the template's active tx (one
  transaction, one commit).
- **2. Tx↔thread binding:** the tx starts and commits on the **caller thread**, the
  same thread the probes already returned to. No thread hop.
- **3. Ambient actor / audit:** the persist runs on the caller thread where the
  filter-bound `ScopedValue<AuthContext>` is still in scope, so Envers stamps the
  **real user** (`audit/CurrentUserRevisionListener.java:22-26` reads
  `CurrentUser.userIdOrSystem()` / `via`). Correct for user-initiated discovery.
  For the SYSTEM-scoped connectivity job the caller thread is unbound → `via=SYSTEM`,
  also correct (`ConnectivityCheckJob.java:36-42`).
- **4. Testability:** the shipped test drives it under
  `@Transactional(propagation = NOT_SUPPORTED)` so the probe phase has no ambient tx
  and the template really commits; the fake `SshExecutor` records
  `isActualTransactionActive()` per exec
  (`DiscoveryServiceTest.java:171-194`). Because a `NOT_SUPPORTED` method **commits**
  into the shared in-memory H2 (`DB_CLOSE_DELAY=-1`), the test registers a unique
  owner and hand-deletes every committed row in a `finally`
  (`DiscoveryServiceTest.java:196-213`) — the isolation hazard 013 calls out
  (013 spec, lines 182-187). Not trivial, but self-contained.
- **5. Readability / consistency:** it introduces the one non-declarative tx idiom in
  the codebase. Its strength is that it reads as exactly what it is — *"an explicit
  transaction boundary wrapped around this block of collaborator calls"* — which is
  precisely the case declarative `@Transactional` cannot express (you cannot
  annotate "these five calls, but not the probes above them"). Two files use it and
  both carry a Javadoc paragraph explaining why.
- **6. Rollback / propagation:** a `RuntimeException` inside the callback marks the
  template's tx rollback-only and propagates; nested `REQUIRED` writes share that
  fate (all-or-nothing). Default propagation is `REQUIRED` — correct here (we *want*
  the nested writes to join, not island themselves in `REQUIRES_NEW`).

**Pros:** minimal moving parts; no new bean; boundary is visible and local; correct
actor stamping by construction; already shipped and tested.
**Cons:** the lone imperative-tx idiom (a reader must know why it's not `@Transactional`);
needs the `NOT_SUPPORTED` + manual-cleanup dance to unit-test the "no tx during
probe" assertion against a shared DB.

### Option B — bean refactoring + declarative `@Transactional`

Extract the persist block into a small collaborator bean whose public method is
plain `@Transactional`. The orchestrator calls it as an **external** bean call, so
the proxy intercepts and one tx spans the nested `REQUIRED` writes — same runtime
result as A, expressed declaratively. Named the "acceptable alternative" in 013.

*Sketch:*

```java
@Service
class DiscoveryPersister {                     // new collaborator bean
    // ...injects RecipeService/ActionService/ApprovalService...
    @Transactional
    List<DiscoveredRecipe> persist(String machineId, List<ProposedRecipe> proposals) { ... }
}

@Service
public class DiscoveryService {
    private final DiscoveryPersister persister;   // injected, not `this`
    public List<DiscoveredRecipe> discover(String machineId) {
        Machine machine = machineService.requireMachine(machineId);
        List<ProposedRecipe> proposals = probeAll(machine);   // no tx open
        return persister.persist(machineId, proposals);       // external call → proxy → one tx
    }
}
```

- **1. Proxy / self-invocation:** this is the canonical *fix* for the self-invocation
  trap — the call crosses a bean boundary, so the proxy's tx advice runs and one tx
  opens. The nested `REQUIRED` writes join it, exactly as in A.
- **2. Tx↔thread binding:** still the **caller thread** — an injected-bean call is an
  ordinary synchronous method invocation, no thread hop. Same binding as A.
- **3. Ambient actor / audit:** same as A — the persist runs on the caller thread,
  `CurrentUser` still in scope → real user stamped for discovery, `SYSTEM` for the
  job. **No actor loss.** (This is the decisive contrast with C.)
- **4. Testability:** the **easiest** of the three. The collaborator is an ordinary
  `@Transactional` bean; a `@DataJpaTest` slice imports it and the default
  rollback-per-test applies — no `NOT_SUPPORTED`, no shared-DB commit, no manual
  row cleanup. The "probes ran with no ambient tx" assertion is still expressible by
  driving `discover(...)` and inspecting the fake executor, but the *persist* half is
  now a plain rollback-friendly declarative test.
- **5. Readability / consistency:** aligns with the codebase's one-declarative-idiom
  norm — every write is `@Transactional`, no imperative template to explain. **Cost:**
  it proliferates beans. ARCH is explicit that responsibility lives in the class-name
  suffix, one `service` layer, no `usecase` layer (`ARCH.md:173-177`). A
  `DiscoveryPersister` / `*Persister` is a *mechanically-motivated* bean (it exists
  only to own a proxy boundary), not a *domain-motivated* one — a mild smell against
  "one business layer". If several paths need it, a `*Persister` pattern could read
  as a shadow usecase layer, which ARCH deliberately rejected. For the connectivity
  job the extracted collaborator would be `applyStatus(Probed)` — a one-liner bean,
  arguably more ceremony than the `tx.executeWithoutResult` it replaces.
- **6. Rollback / propagation:** identical semantics to A (default `REQUIRED`,
  rollback on `RuntimeException`, nested writes join). If a path ever needed the
  persist to be isolated from an outer tx, `@Transactional(REQUIRES_NEW)` is trivially
  expressible on the collaborator — marginally cleaner to declare than to configure on
  a template. Not needed today (neither path runs inside an outer tx).

**Pros:** declarative, matches the house style; trivially unit-testable with plain
rollback; `REQUIRES_NEW` (if ever needed) reads naturally; correct actor stamping.
**Cons:** adds a bean whose only reason to exist is the proxy boundary — pressure on
the "no usecase / one service layer" convention; for the trivial one-write job path
it's more ceremony than the template.

### Option C — `@Async` method returning `CompletableFuture`, caller joins

Make the persist an `@Async` method on a separate bean (so it runs on a Spring
`TaskExecutor` through an async proxy that *also* honors `@Transactional`), returning
`CompletableFuture<T>`; the caller does `future.join()`.

*Sketch:*

```java
@Service
class DiscoveryPersister {
    @Async @Transactional
    CompletableFuture<List<DiscoveredRecipe>> persist(String machineId, List<ProposedRecipe> proposals) {
        return CompletableFuture.completedFuture(/* nested REQUIRED writes */);
    }
}
// caller:
List<DiscoveredRecipe> result = persister.persist(machineId, proposals).join(); // blocks
```

- **1. Proxy / self-invocation:** `@Async` is *also* proxy-based, so it too must be an
  external bean call (same self-invocation rule as B). On the async proxy,
  `@Transactional` is honored — the tx opens **on the pool thread** that runs the
  method. So interception is restored, but on the wrong thread (see below).
- **2. Tx↔thread binding — C buys nothing here.** The tx starts and commits on the
  **async pool thread**, while the caller immediately blocks on `future.join()`. So
  we added a thread hop and a pool dependency for a persist the caller must *wait for
  anyway*. Compared to A/B's synchronous short tx on the caller thread, the wall-clock
  work is identical (probes already returned; the persist is the only remaining work);
  C just performs it one thread over while the original thread parks. There is no
  overlap to exploit — the caller has nothing else to do — so the async machinery is
  pure overhead. **Contrast the genuine async precedent:** `RunService.submitAfterCommit`
  (`RunService.java:251-262`) is fire-and-forget *after commit* and the caller
  **returns immediately** with a `QUEUED` run — the request never joins the worker.
  That is where async pays: a long-running side effect the caller should not wait on.
  Discovery-persist is the opposite: short, and the caller needs its result.
- **3. Ambient actor / audit — the deciding factor against C.** `CurrentUser` is a
  `ScopedValue` (`common/CurrentUser.java:25`), which is **thread-confined and not
  inherited by pool threads**. Discovery is **user-initiated**. If the persist runs on
  an async pool thread, `CurrentUser.CURRENT.isBound()` is `false` there, so the
  Envers listener stamps `userId = null` and `via = SYSTEM`
  (`CurrentUser.java:68-70` `userIdOrSystem()`; `CurrentUserRevisionListener.java:24-25`
  falling back to `Via.SYSTEM`). Result: **a user's discovery is silently
  mis-attributed to SYSTEM in the audit trail** — a correctness regression, not a
  performance one, and silent (no exception). This is precisely the thread-boundary
  hazard the codebase already hit and documented: spec-008 found the MCP SDK dispatched
  tool handlers off the request thread where the `ScopedValue` would not follow, and
  fixed it with `immediateExecution(true)` so tools run on the token-bound request
  thread ([008](./008-done-mcp-write-and-run-tools.md), lines 87-92;
  `README.md` "Resolved (shipped in 008)"; `ARCH.md:160-163, 232-240`). C would
  *re-introduce* the same class of bug on the discovery path. Avoiding it means
  explicitly re-propagating the `AuthContext` into the async task
  (capture `CurrentUser.optional()` at submit, re-bind with `CurrentUser.runWhere(...)`
  inside the task) — extra machinery that exists only to undo the thread hop C
  introduced.
  - **Asymmetry worth noting:** the **connectivity job** is SYSTEM-scoped anyway
    (`ConnectivityCheckJob.java:29-42` — no `AuthContext` bound, writes stamp
    `via=SYSTEM` by design). There, C's actor-loss would be a *no-op* — the pool thread
    stamping `SYSTEM` is exactly what the job wants. So the actor argument kills C for
    **discovery** but not for the **job**. That said, the job *still* gains nothing from
    C on axis 2 (it already joins each future to apply the result on the job thread),
    so C remains unjustified there too — just for a weaker reason.
- **4. Testability:** the heaviest. Tests need a real (or synchronous) `TaskExecutor`
  wired for `@Async`, plus future resolution, plus — to catch the actor regression —
  an assertion on the stamped revision's `userId`/`via`. Contrast B, which is a plain
  rollback-per-test. (Note: an `@Async` method that returns a *joined* value under a
  synchronous test executor also collapses the very thread hop the design is about,
  so the test may not even exercise the real threading — making the actor bug easy to
  miss in tests, which is worse.)
- **5. Readability / consistency:** `@Async` + `join()` reads as concurrency, and a
  reader will reasonably ask "what runs in parallel?" — the honest answer is *nothing*,
  which is a code smell. It also adds a second executor concept next to `runExecutor`.
- **6. Rollback / propagation:** an exception in an `@Async` method returning a future
  is **captured in the future** and only surfaces at `join()` (wrapped in
  `CompletionException`) — rollback still happens on the pool-thread tx, but the
  caller's exception handling changes shape (unwrap the `CompletionException`). More
  surprising than A/B's direct throw. `REQUIRES_NEW` vs `REQUIRED` is moot because the
  async tx is a *fresh* tx on a fresh thread regardless — you cannot join an outer
  caller tx across the thread boundary even if you wanted `REQUIRED` semantics.

**Pros:** honors `@Transactional` via a proxy (no self-invocation); *would* matter if
the caller had independent work to overlap or wanted fire-and-forget.
**Cons:** for this use case the caller must join, so **no async benefit** (§2);
**silent actor mis-attribution on the user-initiated discovery path** (§3) unless
extra re-propagation machinery is added; hardest to test and the test easily hides the
bug; reads as concurrency where there is none; captured-exception semantics.

## Open Questions

- **What would have to be true to switch away from A?** A is correct and shipped.
  The only real pressure on it is *stylistic* — it is the one non-declarative tx idiom
  in a declarative-`@Transactional` codebase. A switch is only worth it if we decide
  that "no imperative tx anywhere" is a convention worth a bean per boundary. That is a
  taste call, not a correctness one.
- **Is B worth refactoring 013 toward?** B was 013's own "acceptable alternative". It
  is the *only* option that is both correct on the actor axis (caller-thread persist)
  *and* declarative/rollback-testable. The cost is bean proliferation against ARCH's
  "one service layer, no usecase" rule (`ARCH.md:173-177`). Open question: does a
  `*Persister` collaborator read as a legitimate service, or as a mechanical
  proxy-boundary bean (a shadow usecase)? For **discovery** (a real multi-write persist
  block) B is defensible; for the **connectivity job** (a one-line status write) B is
  probably over-engineering vs the template. A reasonable middle position: leave the
  job on A, consider B only for discovery if/when the persist block grows.
- **Is C ever justified here?** Leaning **no.** On the user-initiated discovery path C
  is actively wrong (silent SYSTEM mis-attribution) without extra re-propagation
  machinery, and even correct it buys nothing because the caller must join a short
  persist (§2). On the SYSTEM-scoped job the actor loss is harmless, but C still adds a
  redundant thread hop for a result the job waits on. C would only make sense for a
  genuinely fire-and-forget, caller-doesn't-wait side effect — which is the niche the
  existing `submitAfterCommit`/`runExecutor` pattern already fills, and fills without
  pretending to be transactional.

## Leaning (not a decision)

**Keep A as shipped; regard B as the sanctioned refactor target *if* the codebase
later decides to eliminate imperative tx in favor of an all-declarative style — and
then only for discovery, not the trivial job write. Reject C for this use case** on
two independent grounds: it delivers no async benefit when the caller must join (§2),
and it silently mis-attributes user-initiated discovery to SYSTEM because
`ScopedValue` does not cross the pool-thread boundary (§3) — the exact hazard 008
already had to defeat. If this hardens into a decision, it graduates to a spec.
