# 052 — MCP file transfer gate

> **Design reference:** [`052-assets/mcp-file-transfer-gate-mock.html`](052-assets/mcp-file-transfer-gate-mock.html)
> — a clickable mock of the flow, built in the in-place spec-012 design system. It shows one
> **shape** for the answer (notification-gated package review) so the trade-offs below are
> concrete. It is **not** a decision, and where it takes a position it says so in its own
> annotations overlay.

## Problem

An agent over MCP can register machines, author recipes, and run **approved** actions — but it
cannot put a **file** on a machine. Real operational asks ("ship this patched config", "drop this
cert bundle", "seed the runner with an init script") have no path today that isn't a human copying
bytes by hand, or an operator widening a custom recipe until it is a general-purpose write
primitive — which is worse, because it launders an arbitrary write through a gate that only pinned
the *command*.

Adding file transfer collides head-on with the invariant the whole app is built around (ARCH.md's
gate enforcement points; spec-004; the MCP surface's own banner: *"There is no approve tool"*):
**everything an agent causes to happen on a machine passes a human gate first, and what was
approved is pinned by content hash** (spec-015). A file write is strictly more dangerous than a
pinned command, because the payload is arbitrary. So the review surface has to carry the *payload*,
not just a command line — and the pin has to cover the bytes.

Three sub-tensions make this a concern rather than a spec:

1. **The gate needs a "right now" surface.** The natural UI for *an agent is asking for something*
   is a notification, but the existing `.toast` (app.css) is a centred, 2.6-second,
   non-interactive confirmation. It cannot hold a decision, and a gate you can miss by looking
   away is not a gate. Whatever we build is a new component with its own dismissal semantics.
2. **A request is only reviewable if you know why.** Nothing on the MCP surface currently requires
   a model to state intent. A transfer without a stated reason is unreviewable in practice — you
   would be approving bytes with no theory of what they are for.
3. **Nobody may be watching.** Runs are synchronous and operator-initiated; a transfer request is
   agent-initiated and arrives whenever. The app needs an answer for a request that is never
   looked at.

## Hypotheses / Options

### A — Where the gate lives

- **A1 · Notification + queue** *(what the mock draws)* — a persistent, actionable notification
  announces the request; `#/transfers` is the durable queue and the notification is only an
  accelerator. Dismissing never decides.
  *Pro:* immediate, and safe if ignored. *Con:* a genuinely new UI component; notification
  semantics (stacking, dedup, cross-tab) are all new surface.
- **A2 · Queue only** — no notification; requests accumulate under a nav badge.
  *Pro:* much smaller; nothing new but a screen. *Con:* latency is unbounded in practice — the
  agent is blocked while a human isn't looking, which is the common case for a one-operator tool.
- **A3 · Recipe-shaped** — model transfer as a `CUSTOM` recipe/action so it rides the *existing*
  gate with no new gate code.
  *Pro:* by far the cheapest architecturally; reuses `ApprovalService`/`RunService` and spec-015
  pinning verbatim. *Con:* the approval semantics are wrong — approving "send a file" once
  approves *every future payload*, unless the payload itself is inside `ActionSnapshot.hash`, at
  which point it is not really a recipe any more. **Needs an explicit answer before it is dropped.**

### B — Unit of review

- **B1 · Package, atomic** *(mock)* — N files + one reason, approve or deny as a whole.
- **B2 · Per file** — every file is its own decision. Safer, but a 4-file cert bundle becomes four
  notifications.
- **B3 · Package with per-file exclusion** — approve the package minus specific files.
  *Con:* the agent then receives a partial delivery it did not plan for; failure semantics on the
  agent side get complicated.

### C — What pins the payload

- **C1 · Hash at request, re-verify before write** — the spec-015 analogue. Requires the bytes to
  be **staged server-side at request time**, which raises: where do pending bytes live (H2 blob vs
  a staging directory under `./data`), what is the size ceiling, and what reaps the staged bytes of
  a denied/expired package.
- **C2 · Agent supplies bytes at approval time** — nothing to stage.
  *Con:* a TOCTOU hole of exactly the kind spec-015 closed; you would approve a manifest and
  receive different bytes. Recorded so it is rejected on the record, not by omission.

### D — Undecided requests

- **D1 · Expire** after a window *(mock uses 24h — an arbitrary placeholder)*.
- **D2 · Wait forever** — the queue is the record; nothing is lost, nothing is auto-decided.
- **D3 · Reconcile like runs** — spec-016's `RunReconciler` precedent: a pending package that
  survives a restart is resolved (expired/failed) rather than resurrected.

### E — Destination safety

Largely independent of A–D, and probably where the real risk sits:

- Per-machine **writable roots**, and what happens outside them (the mock shows a `sudo` badge and
  a warning banner, and still allows approval).
- Overwrite vs create-only; whether a backup copy is implicit.
- File **mode and ownership**, and whether the agent may request them (the mock lets it).
- **Path traversal and symlinks** — `dest` + relative paths inside the package are attacker-shaped
  input even when the "attacker" is a well-meaning model.
- Interaction with S-risks in ARCH.md's deferred register — needs a pass to see which apply.

### F — The reason

Required free text on the tool call *(mock)*. Open: how hard to lean on it. It is **written by a
model**, so it is a claim, not evidence — the mock renders it in mono, attributes it to the agent,
and labels it unverified. Alternatives: a structured intent enum, or requiring a linked run/recipe
as corroboration.

## Open Questions

1. **Does A3 (recipe-shaped) actually die?** If the payload hash goes into the action snapshot, is
   that a recipe with a blob attached — cheap and consistent — or a category error that corrupts
   what "approved action" means?
2. **Where do pending bytes live**, what is the size ceiling, and who reaps them? This decides
   whether C1 is cheap or invasive against the H2 file DB.
3. **Package atomic or per-file** (B) — and if atomic, is partial approval genuinely unnecessary?
4. **Expiry** (D): a window, forever, or reconciliation-driven? If a window, what is it, and is it
   per-package or global?
5. **Writable roots** (E): is there a per-machine allow-list, or is any path approvable as long as
   the human sees the warning? Does a privileged destination need a stronger confirmation than a
   click — e.g. spec-050's type-the-name gate?
6. **Notification mechanics** (A1): does it need a server push (SSE, as spec-019 already does for
   connectivity) or is poll-on-navigate enough? What happens with two tabs open?
7. **What does the agent see** while a package is pending — a blocking call, a poll-able id, or an
   immediate "pending" return? This shapes the MCP tool signature more than the UI does.
8. **Does this stay outside the recipe/action model entirely**, i.e. a genuinely second gate? If
   so, `GateArchTest` and the ARCH.md enforcement-point list both need extending, and "the gate"
   stops being singular.

## Notes

- **Non-negotiable regardless of option:** MCP gains no approve tool. The agent proposes; only the
  UI decides. Any option that lets an agent both request and release a transfer is out.
- Touches / builds on: **004** (approval gate), **008** (MCP tool surface), **012** (design system,
  `.toast`), **015** (content pinning), **016** (reconciliation precedent), **019** (SSE precedent),
  **043/044** (responsive shell, drawer-based approval UX).
- The mock deliberately reproduces the current UI **including** an apparent CSS bug it surfaced:
  `.nav-toggle { display:none }` (app.css:128) is overridden by `.btn { display:inline-flex }`
  (app.css:258), so spec-043's "Menu" toggle never hides on desktop. Unrelated to this concern —
  noted here only because the mock shows it and it should not read as a mock artefact.
