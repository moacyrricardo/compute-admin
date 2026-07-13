# 031 — Deferred follow-ups triage

> Concern — a roundup of every deferred implementation note and spec-eval finding
> across the specs and merged PRs. Each is re-asked: **is it still something we need
> to do?** No decisions here — this is the triage worklist.

## Problem

Across the built specs (`*-done-*.md` Known-Gaps / Implementation-notes / Delivered
sections) and the spec-eval reports posted on merged PRs (notably **#38** app-ops and
**#39** fleet monitoring), a long tail of deferred notes, disclosed divergences, and
"worth addressing" findings has accumulated. They live scattered where they were
raised and were never triaged as a set. This concern consolidates them so each can be
decided independently — **keep** (turn into work), **drop** (moot / superseded), or
**already addressed** (resolved since it was written) — without re-indexing the
risks and specs that are already tracked elsewhere (see *Not re-listed here*).

Note: several sources point at the same item; those are merged into one entry that
lists all its sources. One item the triage prompt expected as catalog row "H8" (the
dead server-side monitor helpers) turned out **never to have been entered** in the
catalog's hardening table — it lives only in the #38/#39 evals and spec-029 — so it
appears below as a full open question (first entry), not a cross-reference.

## Open Questions

### Monitoring / fleet

- **Dead / misplaced server-side monitor helpers** — `MonitorDtos.opsForApp` puts
  app→ops correlation logic in the `api` DTO layer whose only caller is a test
  (production correlation runs client-side in `app.js`); `MonitorDtos.memPctOfHost`
  and `parseHostMemTotalMb` are never invoked on any server path and duplicate the
  authoritative client-side mem-% computation, so the metric has two sources of truth.
  _Source:_ PR #38 eval (Architectural Fit + Summary), PR #39 eval (Architectural Fit
  + Summary), spec-029 Context / Delivered. (The prompt called this catalog "H8"; no
  such row exists in `specs/README.md`.)
  _Still needed?_ **do** — move correlation into `monitor/service` and wire the mem-%
  helper into a server path so there's one tested source of truth · **drop** — delete
  the server helpers since only the client needs them · **already addressed** — if a
  later change removed or wired them.

- **No server-side fleet poller** — polling is entirely client-driven; closing the
  browser tab stops all probing, and there is no background sampler.
  _Source:_ spec-029 Known Gaps (flagged as ties-to 016/S7).
  _Still needed?_ **do** — add a bounded server-side poller · **drop** — client-driven
  polling is sufficient for a single-operator local tool · **already addressed**.

- **mem-% is RSS-only** — swap-inclusive footprint is not modelled, and the
  denominator is always host RAM (container cgroup memory limits are ignored).
  _Source:_ spec-029 Known Gaps.
  _Still needed?_ **do** — model cgroup limits / swap · **drop** — RSS÷host-total is
  the honest cheap v1 metric · **already addressed**.

- **No fleet aggregate rollup** — there is no "N/M apps DOWN" summary strip; state
  lives only in the per-machine sections.
  _Source:_ spec-029 Known Gaps, spec-024 Known Gaps.
  _Still needed?_ **do** — add a summary banner · **drop** — per-machine sections
  carry the state · **already addressed**.

- **No monitoring history / time-series** — every poll is a fresh snapshot; trends and
  charts do not exist.
  _Source:_ spec-023 Known Gaps, spec-024 Known Gaps (concern 020 Open Q4).
  _Still needed?_ **do** — a history/trend spec · **drop** — current-state is enough ·
  **already addressed** (or folds into the 020 umbrella — see *Not re-listed here*).

- **Cross-machine app identity is a bare `app-name` string** — no alias map, so a
  container-name vs probe-name mismatch splits one real app across two cards, and two
  unrelated apps sharing a name across machines are compared as the same app.
  _Source:_ spec-022 Known Gaps, spec-024 Known Gaps, spec-029 Known Gaps.
  _Still needed?_ **do** — an alias map · **drop** — operators choose the names ·
  **already addressed**.

- **Brittle client-side `top`/`free`/`df` parsing** — v1 parses the common GNU layout
  and degrades to raw text elsewhere; a `/proc`-based, locale-proof variant is the
  refinement.
  _Source:_ spec-023 Known Gaps, spec-024 Known Gaps.
  _Still needed?_ **do** — `/proc`-based parsing · **drop** — GNU + raw-text fallback
  is acceptable · **already addressed**.

- **CPU snapshot accuracy** — `top -bn1`'s single sample reports instantaneous %Cpu; a
  two-sample `/proc/stat` delta is more accurate but needs two reads.
  _Source:_ spec-023 Known Gaps.
  _Still needed?_ **do** — two-sample delta · **drop** — one-shot template is fine ·
  **already addressed**.

- **Per-poll `Run` rows / non-persisted "read-now" path** — a fast cadence over many
  apps creates many rows; pruning bounds storage but the browser still issues the
  requests, and a non-persisted read path is the eventual optimisation.
  _Source:_ spec-022 Known Gaps, spec-024 Known Gaps (concern 020 Open Q1).
  _Still needed?_ **do** — a read-now path · **drop** — pruning is enough ·
  **already addressed**.

### App-ops / run engine

- **Cancel in the QUEUED pre-registration window** — cancelling a run still QUEUED marks
  it STOPPED, but the remote command may still execute orphaned in the tiny window
  before the channel is registered (cancel is scoped to RUNNING follow-mode).
  _Source:_ PR #38 eval (Deferred), spec-026 Implementation notes.
  _Still needed?_ **do** — close the window · **drop** — acknowledged gap, no gate
  impact · **already addressed**.

- **Cancelled run persists empty stdout/stderr** — `finish` no-ops for a STOPPED run,
  so its persisted output stays empty (output was only streamed live).
  _Source:_ PR #38 eval (Deferred).
  _Still needed?_ **do** — persist the partial output · **drop** — live stream was the
  only contract · **already addressed**.

- **`LocalDevSshExecutor` cancellation is a no-op** — follow-mode under the offline
  `localssh` profile runs to its own timeout; only the real `MinaSshExecutor` cancels.
  _Source:_ spec-026 Known Gaps.
  _Still needed?_ **do** — implement local cancel · **drop** — dev-only profile ·
  **already addressed**.

- **Regex `app-name` ops never surface on cards** — facade correlation is
  `ALLOWED_SET` membership, so a regex `app-name` is not enumerable and its ops don't
  appear; relatedly, `ParamBinder.targetApps` still carries a dead-defensive REGEX
  branch that `ActionService` now forbids from ever being authored.
  _Source:_ spec-026 Known Gaps, PR #38 eval (Spec Fidelity).
  _Still needed?_ **do** — support regex correlation / drop the dead branch · **drop**
  — ALLOWED_SET is the intended shape · **already addressed**.

- **Unused Flyway V12 slot** — both new enum values (`RecipeType.SYSTEMD`,
  `RunStatus.STOPPED`) are `@Enumerated(STRING)`, so V12 was left unused.
  _Source:_ spec-026 Implementation notes, PR #38 finish comment.
  _Still needed?_ **do** — nothing (housekeeping note only) · **drop** — harmless ·
  **already addressed** (a later migration may have consumed it).

- **MCP progress consumer deferred** — the `RunOutputHub` fans out to multiple
  subscribers by design, but only the SSE subscriber exists; the MCP progress consumer
  was said to "land with spec 008" and does not appear to have been built.
  _Source:_ spec-005 Known Gaps.
  _Still needed?_ **do** — build the MCP progress consumer · **drop** — MCP callers
  don't need live progress · **already addressed** (verify in 008's surface).

- **Run output not persisted incrementally** — the DB sees one write at completion, so
  a crash mid-run loses that run's output (the row is later reconciled to INTERRUPTED
  by spec-016, but the output is gone).
  _Source:_ spec-005 Known Gaps, spec-016 Known gaps.
  _Still needed?_ **do** — incremental append · **drop** — crash-mid-run output loss
  is acceptable · **already addressed**.

- **Reconciler can't confirm the real remote outcome** — orphaned rows are recorded
  `INTERRUPTED` with `exitCode = -1`; there is no re-probe of the target to learn
  whether the command actually completed.
  _Source:_ spec-016 Known gaps.
  _Still needed?_ **do** — re-probe on reconcile · **drop** — sentinel note is enough ·
  **already addressed**.

- **Multi-instance operation is unsupported** — the boot reconciler's "sweep all
  non-terminal rows" is correct only under the single-instance `AUTO_SERVER` invariant;
  running multi-instance needs a per-run lease / owning-instance id.
  _Source:_ spec-016 Known gaps.
  _Still needed?_ **do** — process-scoped sweep · **drop** — single-instance is the
  product decision · **already addressed**.

- **Run output stored/streamed unredacted** — service commands often print secrets, and
  the `Run` record + unencrypted H2 DB then hold them; there is no redaction or
  retention policy.
  _Source:_ spec-005 Known Gaps.
  _Still needed?_ **do** — redaction/retention · **drop** — same local-box boundary as
  S2 · **already addressed**.

### Discovery / tags / blueprints

- **Auto-tagging is not a continuous reconciler** — it runs at registration + first
  reach only; there is no re-detect on OS change or a re-imaged host.
  _Source:_ spec-018 Known Gaps.
  _Still needed?_ **do** — a reconciler · **drop** — one-shot is enough · **already
  addressed**.

- **OR-only multi-tag filter** — no AND / NOT tag expressions.
  _Source:_ spec-018 Known Gaps, spec-029 Known Gaps.
  _Still needed?_ **do** — AND/NOT filters · **drop** — a single OR-set is enough ·
  **already addressed**.

- **Blueprints: no auto-instantiation, drift not enforced** — a blueprint is not
  auto-instantiated onto newly-registered machines matching a tag, and drift (an
  instantiated action edited on its machine) is visible via provenance but not forced
  back into sync.
  _Source:_ spec-010 Known Gaps.
  _Still needed?_ **do** — a "keep tag X in sync with blueprint Y" listener + drift
  enforcement · **drop** — explicit instantiation + visible drift is enough ·
  **already addressed**.

### Auth / accounts / audit

- **"Sign in with Google" (GIS) never wired in the UI** — the button only toggles a
  paste-a-credential textarea; there is no Google Identity Services integration. This is
  a strong moot candidate: spec-014 **replaced** Google sign-in with email+password.
  _Source:_ spec-012 Deferred (S1′).
  _Still needed?_ **do** — n/a · **drop** — superseded by 014's email+password auth ·
  **already addressed** — confirm the dead Google-paste UI was removed.

- **No password reset flow** — the operator resets via the DB.
  _Source:_ spec-014 Known Gaps.
  _Still needed?_ **do** — a reset spec · **drop** — DB reset is fine while local ·
  **already addressed**.

- **Envers audits nothing yet** — Envers is configured and `CurrentUserRevisionListener`
  is wired, but was noted as dormant because no owned entities existed at the time.
  Owned entities (Machine/Recipe/Run) now exist, so this may already be live.
  _Source:_ spec-011 Known Gaps.
  _Still needed?_ **do** — turn on auditing for the owned entities · **drop** — no audit
  trail needed locally · **already addressed** — verify whether auditing is now active.

- **Per-entity ownership retrofit** — spec-011 said owner columns + cross-user-404 tests
  would land with specs 003/004/005/010; all four are now `done`, so this is likely
  resolved.
  _Source:_ spec-011 Known Gaps.
  _Still needed?_ **do** — finish any gaps · **drop** · **already addressed** — confirm
  ownership + 404 coverage exist across those entities.

### Docs / process / cross-cutting

- **Test-coverage follow-ups** — no test asserts the `changedSinceApproval` field flips
  true on drift (spec-012), and the best-effort graceful-drain integration test was left
  unimplemented as timing-flaky (spec-016).
  _Source:_ spec-012 Deferred, spec-016 Known gaps.
  _Still needed?_ **do** — add the tests · **drop** — the underlying conditions are
  already covered at the service layer · **already addressed**.

- **`done`-marking was not the final commit** — on the 029 branch a behavioral commit
  (`085a9cc`) landed after the commit that marked the spec `done` (`e049264`), against
  the CLAUDE.md rule that the final commit updates the spec.
  _Source:_ PR #39 eval (Change Division).
  _Still needed?_ **do** — treat as a process reminder for future closeouts · **drop** —
  one-off, no artefact impact · **already addressed**.

- **Style / naming nits from the evals** — `MinaSshExecutor.cancellable` →
  `liveExecsByCancelKey`; `MonitorService.OpsAction` vs DTO `AppOpView` → align to
  `AppOp`; `ActionService.reservedAppName` (boolean) → `isReservedAppName`; and
  `RunRS.children` / `MonitorDtos` use fully-qualified `java.util.List` /
  `java.util.regex.Pattern` / `java.util.Locale` inline instead of imports.
  _Source:_ PR #38 eval (Naming), PR #39 eval (Architectural Fit).
  _Still needed?_ **do** — a small rename/cleanup pass · **drop** — cosmetic · **already
  addressed**.

## Not re-listed here

This doc is the triage of *loose notes* only. Already-tracked work is not re-indexed:
the catalog's open hardening rows **H4** (`DatabaseDiscoverer` fixed backup filename)
and **H7** (`ActionSnapshot` canonical-serialization escaping) in `specs/README.md`
(there is no H8 row — see the first Open Question above); the ARCH.md **S1–S9**
deferred-risk register (host-key S3, argument-injection / vetted-param-type-library
S4, passwordless-`sudo` / login-user-only-probe S5, rate-limiting-and-concurrency S7,
transport/GIS S1′, secrets-at-rest S2, S8 resolved, S9→028); and the open
concerns/specs **017** (tx boundary), **020** (monitoring umbrella), **027**
(signal-driven unreachability), **028** (MCP identity), **030** (docker container
monitoring, open PR), plus parked **009** (cloud import) and todo **015**
(content-pinning).
