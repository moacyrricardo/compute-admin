# 072 — App-monitor management-port & status-naive actuator detection

## Problem

The app-monitor discoverer (`AppMonitorDiscoverer`) classifies a listening service as a
**springboot monitor** (with `/actuator/*` probes) only if actuator answers, and it decides that
with a single probe against **the listener's own port**:

```java
// AppMonitorDiscoverer.java:321
if (family == Family.SPRINGBOOT && !respondsToActuator(session, listener.port())) {
    family = Family.HTTP;   // "http app monitor" — GET / liveness + process metrics only
}
// :641-647
private boolean respondsToActuator(SshSession session, int port) {
    return !Probes.lines(session,
        List.of("curl", "-sf", "-m", "2", "http://127.0.0.1:" + port + "/actuator/health")).isEmpty();
}
```

This is naive along **three axes** — port, status, and address — and the first two produce real,
observable misbehaviour:

**1. Management port on a different socket (`management.server.port`).** A Spring Boot app that
serves traffic on 8080 and actuator on 8081 is **one JVM/PID with two listening sockets**. The
resolve loop keys everything on `(addr, port)` (`Listener.key()` = `addr + "|" + port`, :1450-1452)
with **no per-PID collapse**, so both sockets resolve independently:

- **:8080** → `classify()` marks it SPRINGBOOT (java, :764-773), the own-port actuator probe on 8080
  returns 404 → `-f` fails → downgraded to **`http app monitor`**, mislabeled *"no actuator
  responded"* (the `Family.HTTP` description at :96) even though the app has actuator one port over.
- **:8081** → also SPRINGBOOT; the own-port probe on 8081 answers → stays **`springboot monitor`**,
  but its **identity port is the management port**, not the service port.

Net: one app is discovered **twice** — two recipes to approve, same app name on two ports in two
families, and the JVM's PSS/CPU footprint is measured by **both** records (every footprint probe
resolves the PID from whatever port it is handed — :119-129, :146-168, :183-196 — so the same JVM
is counted twice). spec-063's native-consumer grouping is **per recipe** (`MonitorService.java:184-192,
318-340`), and the two items live in different recipes, so the duplication survives all downstream
aggregation and reaches the fleet UI as two `MonitorAppView`s.

**2. Actuator that answers non-2xx (503 / 401).** `respondsToActuator` uses `curl -sf`, and
`Probes.lines` discards output on a non-zero exit (`Probes.java:36-45`), so **HTTP 503 (health DOWN)
and 401/403 (auth-gated)** both fail the probe and misclassify a genuine Spring Boot app as HTTP —
losing its actuator probes **exactly when they matter most** (an unhealthy app is precisely when
someone installs monitoring). This is the *same root* as #1: detection can't tell "no actuator" from
"actuator answered, non-2xx".

**3. Non-loopback-only actuator bind.** The probe hardcodes `127.0.0.1` (:646), and the approved
run-time template hardcodes it too (:1322), so an actuator bound to a non-loopback address only would
also degrade. (This one is invariant-blocked — see Blockers — and is named here only to be deferred.)

**None of this is contemplated by any existing spec.** `rg -i management src/main` finds zero hits.
spec-029 §6 pins the "nothing on `/actuator/*` responds → http family" rule on the (now-false)
assumption that actuator lives on the app's own port; spec-062 handles the *inverse* multiplicity
(several PIDs on one socket, "lowest PID wins", :459-464) but never one-PID-many-ports. Confirmed
empirically on the `boletim` box, the finding is scoped correctly: that host's actuator was simply
**disabled**, so its `http app monitor` fallback was *correct* — this concern is a latent gap that
bites whenever someone runs `management.server.port` or an auth-gated / unhealthy actuator, not a
`boletim` regression.

## Hypotheses / Options

Investigated read-only against the code by two context-less agents. Effort/risk are relative.

### A0 — Document & accept
No code change; note the limitation. **Untenable as an endpoint** — the "no actuator responded"
label is factually wrong, footprint double-counts, operator approves two recipes for one app.
Acceptable only as the interim state while a spec is authored.

### A1 — Same-PID sibling-port actuator sweep (in-loop)
When a java listener's own-port probe fails (:321), probe the *other* ports owned by the same PID
(derivable from the already-fetched `List<Listener>` — **no new inventory probes**); if one answers,
emit one springboot record, identity = traffic port, management port as metadata.
**Blocked as specified:** the resolve loop is sequential over `ss` output, whose order is arbitrary.
If the 8081 listener is processed *first*, it is emitted as SPRINGBOOT before the 8080 sweep ever
runs. So an in-loop patch at :321-322 is not implementable — it **forces a pre/post-pass and
collapses into A3**. (Most important structural finding.)

### A2 — PID-grouped resolution (restructure the loop)
Rewrite `discover()` (:297-348) to group attributed listeners by PID, resolve once per PID, choose an
identity port. **The correct long-term model** (app = process; ports = attributes; aligned with the
055 app-model direction) — also kills adjacent noise for free (debug/JMX ports, v4/v6 twin binds,
nginx `0.0.0.0:80`+`[::]:80`). **But high blast radius:** collides with spec-062 D1 null-PID survival
(test `discover_BothLoopbackAndAnyBind_...`), changes spec-056 D5 fingerprint-confidence semantics
(`service.defaultPort() == listener.port()`, :340), and churns a large fraction of the ~45 discoverer
test fixtures that assert per-listener emission counts. **Right destination, wrong first step** —
fold into a future app-model spec once merge semantics are proven.

### A3 — Post-hoc merge / reconciliation pass  *(recommended core)*
Leave the per-listener loop untouched; after :348, run a merge pass over `resolved`: for each PID
owning both a SPRINGBOOT record (port M, actuator answered on its own port) and an HTTP record
(port T, downgraded at :321-322), collapse into one SPRINGBOOT record, identity = T, `managementPort
= M`. **Order-independent, minimal test risk** (non-merge cases untouched). Needs: `pid` added to the
internal `Resolved` record (:1462, currently absent), and **PID as the only sound merge key** —
`(context + appName)` is unsafe (blue/green: two JVMs of one jar share both and must never merge).
Requires **one discriminator probe** — a `curl -s -m 2 http://127.0.0.1:T/` per candidate pair
(discovery currently never probes `GET /`) — so a java PID with actuator-on-own-port plus a dead
extra port (e.g. 5005 debug) doesn't mis-merge as `{traffic=5005, management=8080}`.

### A4 — Config-hint driven
Parse `-Dmanagement.server.port=` / `--management.server.port=` from the already-fetched cmdline
(:314), optionally `MANAGEMENT_SERVER_PORT` from `/proc/<pid>/environ` (exact precedent: `envValue()`
:1155-1165, used for PGDATA). **Poor standalone** — the dominant config lives in `application.yml`
inside the fat jar (invisible on cmdline; remote YAML parsing is fragile), and `management.server.port=0`
(random) is unresolvable. **Excellent free complement** to A3: confirms a merge without the `GET /`
discriminator, disambiguates the 3-port case, and names an unprobeable management port. **A4-lite** =
cmdline (+ maybe environ) parse only; skip file reading.

### Rider 1 — status-aware actuator probe  *(fold in — prerequisite for A3)*
Make detection status-aware. Because `Probes.lines` can't see a status on a non-zero exit, the probe
**command itself** must change, e.g. `curl -s -m 2 -o /dev/null -w %{http_code} <url>` (exit 0 for
any HTTP status). Decision table: `2xx|503` ⇒ actuator; `401|403` ⇒ actuator present but gated
(springboot family + a "actuator secured" `sourceNote`; no credential story in the param model today,
so v1 = classify correctly, label the gate); `404`/other/timeout ⇒ not actuator. Optional body-sniff
(`{"status":`) tightens the catch-all-SPA false positive that exists today too. **Same concern as the
management-port gap** — one function, one decision table — and a **correctness prerequisite for the
A3 merge**: an app that is 503 at discovery time produces *two* HTTP records, so the merge has nothing
to merge unless the probe is status-aware first.

### Rider 2 — non-loopback bind  *(name & defer)*
Probing `listener.addr()` instead of hardcoded `127.0.0.1` is cheap on the discovery side
(`canonAddr` :740-751 already normalizes), **but a full fix is invariant-blocked**: the approved probe
templates hardcode `127.0.0.1` as a source-controlled literal (:1322), and the class contract
(:47-49), spec-025 (`025:92`), and ARCH S4 (`ARCH.md:201`) forbid a caller-influenced host segment.
Fixing detection without the template would **manufacture dead approved probes** (classify via
`10.0.0.5:8081`, then forever curl `127.0.0.1:8081`). A run-time `host` component is its own S4-surface
decision. **Defer**; warn against the discovery-side-only half-fix. (Mitigation: `management.server.address`
usually serves all resolvable addresses, so loopback typically works; exclusively-non-loopback is rare.)

## Shared plumbing (the "can the model express two ports" answer)

**Yes**, via the app-folder server-side-enrichment precedent — with one unavoidable cost:

1. `AppPortItem` + nullable `managementPort` (Integer); serialized into side-data by
   `DiscoveryService.toJson` (:316-323; shape-tolerant readers already exist — no migration).
2. `MonitorService.AppPort` + field; `RecipeDtos.AppPortView` (:104); `MonitorDtos.appPortView` (:423);
   `app.js` badge (":8080 · mgmt :8081"). MCP never sees app-port items (no `mcp/` hits) — no wire change.
3. `ParamBinder`: a `MANAGEMENT_PORT_COMPONENT` in `isAppPortComponent` (:148-151) and
   `validateAppPortComponent` (:200-228, reuse the port rule); `ActionService:296-299` wires it for free.
4. `RunService`: enrich per item server-side from side-data keyed `(appName, port)` — a clone of
   `contextFolders` (:324-354) with default = the item's own port, so single-port apps bind identically.
5. `AppMonitorDiscoverer.endpointProbe` (the four SPRINGBOOT actions, :1281-1284) references
   `param(MANAGEMENT_PORT_COMPONENT)` instead of `port`; process/footprint probes keep `port`.
6. **The cost the spec must own:** changing the endpoint templates changes their snapshot hash, so
   **every already-APPROVED springboot `health`/`metrics`/`beans`/`info` action flips to
   `DIFFERS_AWAITING_REAPPROVAL`** on next discovery (`DiscoveryService.java:299-306`) — a one-time
   re-approval per fleet springboot recipe. (The dodge — keep templates on `port` and have `RunService`
   silently substitute the management port for endpoint actions — was rejected: nothing on the persisted
   `Action` marks "endpoint vs process" except its name, so a name-keyed substitution makes the approved
   argv a lie.)

## Blockers (the highest-value findings)

1. **In-loop A1 is unimplementable as specified** — `ss` ordering forces a pre/post-pass → A1 = A3.
2. **`Resolved` carries no PID** (:1462) — any merge must extend the record; `(context+appName)` is an
   unsound merge key (blue/green collision).
3. **Naive A3 without a discriminator mis-merges** debug/JMX siblings — needs a `GET /` probe discovery
   doesn't do today.
4. **`Probes.lines` structurally can't see an HTTP status on a non-zero exit** — the probe *command*
   must change, not just its flags.
5. **Expressing "metrics on X, actuator on Y" reopens approval** on every existing springboot recipe's
   endpoint actions (one-time churn the spec must own).
6. **The non-loopback fix is invariant-blocked** at :1322 / :47-49 (ARCH S4).

## Scope

**One concern, two of three folded in.** The management-port duplication and the 503/401 rider are the
same root defect — `respondsToActuator` is naive along port, status, and address — and the status fix is
a *correctness prerequisite* for the merge. Fold them together. **Name and defer** the non-loopback rider
(own S4 decision; warn against the half-fix). Cross-reference **spec-036** for stale-record cleanup: a
pre-fix mislabeled `http app monitor` recipe/item will linger after any fix until 036's retire/suppress
story lands.

## Recommendation

**A3 (PID-sibling merge pass) + Rider 1 (status-aware probe) + A4-lite (cmdline/environ hint as
corroboration)**, on the shared `managementPort` plumbing. A3 delivers A1's semantics without A1's
ordering trap and without A2's collisions and test churn; it needs **zero new inventory probes**, one
memoized discriminator curl per merge candidate, and confines risk to a new pass plus a nullable field.
Rider 1 is required for the merge to be correct and fixes a real misclassification on its own. The config
hint is nearly free and resolves the only genuinely ambiguous case (3+ ports).

**Defer:** A2's full PID-grouped model (future app-model spec — it also subsumes debug-port and v4/v6
twin-record noise), Rider 2's host param (own S4 decision), and stale-record retirement (spec-036).

## Open Questions

1. **Identity port with 3+ ports** — when a PID owns app + management + debug (5005), the `GET /`
   discriminator picks the traffic port, but confirm the tie-break (HTTP answer ⇒ traffic; bind-address
   heuristic — management typically loopback, traffic wildcard — as a secondary signal). What if two
   ports both answer `GET /`?
2. **401/403 v1 behaviour** — classify as springboot and propose all four endpoint probes (they 401 until
   credentials exist), or propose `health` only, or springboot + a "gated" note and no endpoint actions?
   There is no credential story in the param model today.
3. **Re-approval churn** — is the one-time `DIFFERS_AWAITING_REAPPROVAL` flip on every existing springboot
   recipe acceptable, or does it need a migration/grace path? (Interacts with spec-036.)
4. **Discriminator cost** — is one extra `curl -m 2 GET /` per merge candidate (and per non-HTTP sibling,
   which eats the 2s timeout) acceptable within a discovery pass, given 070's session reuse now amortizes
   the connection?
5. **Does A3 land standalone, or wait for the A2 app-model restructure?** A3 is a targeted fix on today's
   model; A2 is the eventual home. Ship A3 now and migrate later, or hold for A2?
