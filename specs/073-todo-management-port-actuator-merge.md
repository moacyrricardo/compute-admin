# 073 — Management-port actuator merge + status-aware probe

## Context

Resolves concern [072](./072-concern-app-monitor-management-port.md). `AppMonitorDiscoverer`
decides *springboot-vs-http* from a single `curl -sf` against **the listener's own port**
(`AppMonitorDiscoverer.java:321`, :641-647), which is naive along **port** and **status**:

- **Separate `management.server.port`.** A Spring Boot app serving traffic on 8080 with actuator on
  8081 is one JVM/PID with two listening sockets. Dedup is keyed on `(addr, port)` only
  (`Listener.key()`, :1450-1452) with no per-PID collapse, so it is discovered **twice**: :8080
  downgrades to `http app monitor` (mislabeled *"no actuator responded"*, :96) and :8081 stays
  `springboot monitor` but pinned to the **management port** as its identity. The operator approves
  two recipes for one app, and the JVM's PSS/CPU footprint is counted twice (every footprint probe
  re-resolves the PID from whatever port it is handed: :119-129, :146-168, :183-196). The duplication
  survives spec-063's per-recipe consumer grouping (`MonitorService.java:184-192, 318-340`).
- **Non-2xx actuator.** `respondsToActuator` uses `curl -sf`, and `Probes.lines` discards output on a
  non-zero exit (`Probes.java:36-45`), so a **503 (health DOWN)** or **401/403 (auth-gated)** actuator
  fails the probe and misclassifies a real Spring Boot app as HTTP — losing its actuator probes exactly
  when they matter most.

The option space (A0–A4 + two riders) was analysed in 072 and **decided via a `/rich-html:decide`
surface**. The chosen path is **staged**: this spec ships the targeted fix (A3) now; the full per-PID
restructure (A2) is deferred to its own near-term spec (see Known Gaps). A2 subsumes A3, and every
part of this spec except its post-hoc merge pass is a verbatim-reusable prefix of A2 — so A3-first is
the better expected-value path, not throwaway work.

## Decision

Ship, on the shared `managementPort` plumbing:

1. **A3 — post-hoc PID-sibling merge pass.** After the per-listener resolve loop, collapse a PID that
   owns both a `SPRINGBOOT` record (port M, actuator answered on its own port) and an `HTTP` record
   (port T, downgraded because its own-port probe found no actuator) into **one** `SPRINGBOOT` record:
   identity = **T** (the traffic port), `managementPort = M`. Merge key is the **PID** — never
   `(context + appName)`, which is unsound (blue/green: two JVMs of one jar share both).
2. **Confidence-gated merge (decision D4).** Merge only on **positive evidence** — an actuator pair, a
   `-D`/`--management.server.port` cmdline hint, a same-number v4/v6 twin, or a catalog fingerprint.
   With no positive signal, **fall back to per-port emission** (today's behavior is the explicit
   degenerate case). A one-shot `GET /` discriminator (decision D10) guards against mis-merging a dead
   sibling: a java PID with actuator-on-own-port 8080 plus a dead 5005 debug port must **not** merge as
   `{traffic=5005, management=8080}` — merge only when T actually answers HTTP.
3. **Status-aware actuator probe (Rider 1 / decision D2).** Replace `curl -sf …/actuator/health` with a
   status-reading probe and classify by code: `2xx | 503` ⇒ actuator; `401 | 403` ⇒ actuator present
   but gated; `404`/other/timeout ⇒ not actuator. This is a **correctness prerequisite** for the merge
   (an app 503 at discovery otherwise produces two HTTP records with nothing to merge).
4. **401/403 v1 behavior (decision D6).** Classify `SPRINGBOOT`, label the gate in `sourceNote`
   (*"actuator secured"*), and propose **`health` only** — there is no credential story in the param
   model today, so shipping metrics/beans/info probes that 401 by design is dishonest.
5. **ss-path lowest-PID canonicalisation (decision D7).** Extend spec-062's lowest-PID rule (today only
   on the `/proc/net` fallback, :480-487) to the `ss` parse (:246, :674-676), which currently takes the
   **first** PID in the `users:((…))` column. A latent nondeterminism for preforked servers (nginx
   master+workers), and a prerequisite for any PID-keyed logic — fixed now regardless of A2.
6. **A4-lite hint (corroboration).** Parse `-Dmanagement.server.port=` / `--management.server.port=`
   from the already-fetched cmdline (optionally `MANAGEMENT_SERVER_PORT` via the `envValue` precedent,
   :1155-1165) to confirm a merge without the `GET /` discriminator and disambiguate 3+ ports. Never
   read config files.
7. **Accept the re-approval churn (decision D8).** Pointing the endpoint actions at the management port
   changes their approved argv → a one-time `DIFFERS_AWAITING_REAPPROVAL` flip per existing springboot
   recipe (`DiscoveryService.java:299-306`). This is the mechanism working as designed; document it and
   cross-reference **spec-036** for the stale-record cleanup (a pre-fix mislabeled `http app monitor`
   will linger until 036's retire/suppress story lands).

**Deferred (named, not built):** the non-loopback actuator bind (Rider 2 / decision D3 — needs a
runtime host component, an ARCH-S4 surface decision) and the full A2 per-PID restructure.

## Implementation

**Discovery (`AppMonitorDiscoverer`):**
- Extend the internal `Resolved` record (:1462-1464) with `pid` (concern Blocker 2 — it carries none
  today). Merge is impossible without it.
- Replace `respondsToActuator` (:641-647) with a status-reading probe:
  `curl -s -m 2 -o /dev/null -w %{http_code} http://127.0.0.1:<port>/actuator/health`, returning the
  code (`Probes.lines` sees it because exit is 0). Add the decision table; keep the loopback host (the
  non-loopback case is deferred).
- Add a `GET /` discriminator helper (`curl -s -m 2 -o /dev/null -w %{http_code} http://127.0.0.1:<port>/`)
  — discovery never probes `/` today (only `/actuator/health` + `/metrics`, :641-655). Memoize per port.
- **Merge pass** after the resolve loop (~:348): build `Map<pid, List<Resolved>>`; for a PID with a
  `SPRINGBOOT`(M) + `HTTP`(T) pair where the positive-evidence gate passes, replace them with one
  `SPRINGBOOT` record (identity T, `managementPort` M). Otherwise leave both as-is.
- `parseSs`: collect **all** PIDs in the `users:((…))` column and canonicalise to the lowest, matching
  the fallback path (:480-487).

**Shared `managementPort` plumbing (reused wholesale by A2 later):**
- `AppPortItem` (`AppPortItem.java:51-66`) + nullable `managementPort` (Integer); serialized by
  `DiscoveryService.toJson` (:316-323) — shape-tolerant readers exist, **no migration**. Four
  `new AppPortItem(...)` sites in the tree (none in tests).
- `MonitorService.AppPort` + field (:100-111, :247-258); `RecipeDtos.AppPortView` (:104);
  `MonitorDtos.appPortView` (:423); `app.js` badge (`:8080 · mgmt :8081`).
- `ParamBinder`: a `MANAGEMENT_PORT_COMPONENT` in `isAppPortComponent` (:148-151) and
  `validateAppPortComponent` (:200-228, reuse the port rule); `ActionService:296-299` wires it for free.
- `RunService`: enrich per item server-side from side-data keyed `(appName, port)` — a clone of
  `contextFolders` (:324-354) with default = the item's own port, so single-port apps bind identically.
- `AppMonitorDiscoverer.endpointProbe` (the four SPRINGBOOT actions, :1281-1284) references
  `param(MANAGEMENT_PORT_COMPONENT)` instead of `port`; process/footprint probes keep `port`.

**Tests** (`AppMonitorDiscovererTest` — note the non-UTF8 byte; use `rg`/`grep -a`): the harness keys a
`FakeSshExecutor` on exact joined argv, so add fixtures with one PID on two ports —
(a) management-port pair (both `ss` orderings) ⇒ one `springboot monitor`, item `{name, T,
managementPort:M}`, no `http app monitor`; (b) 503 on the management port ⇒ still merges; (c) 401 ⇒
springboot + gated note, `health` only; (d) actuator-on-own-port + dead 5005 ⇒ **no** merge, two records
as today; (e) two JVMs of one jar ⇒ no merge; (f) ss multi-PID users column ⇒ lowest PID wins. Plus a
`ParamBinder`/`RunService` test that `management-port` binds from side-data and defaults to `port`.
Live-verify per the CLAUDE.md sshd-container recipe with a demo app run under `--management.server.port`.

## Known Gaps

- **A2 — full per-PID resolution (own near-term spec).** Restructure `discover()` to resolve per PID
  rather than per `(addr,port)` so the duplicate never forms. A2 subsumes this spec's merge pass (the
  only throwaway part) and additionally fixes debug/JMX card noise, v4/v6 twin records, and multi-port
  footprint double-count in **all** cases (not just the springboot pair). Its still-open policy choices,
  already decided on the 072 decide surface and to be carried into the A2 spec: **preserve non-identity
  ports as a port-set/`sourceNote` annotation** (D5, don't drop debug/JMX silently) and **collapse a
  container's sockets into one item under the container name** (D9, vs spec-061's per-published-port
  shape). The ss-path lowest-PID fix (item 5 above) is a prerequisite already landed here.
- **Non-loopback actuator bind (Rider 2, deferred).** Probing `listener.addr()` instead of the hardcoded
  `127.0.0.1` (:646) is cheap on the detection side, but a full fix needs a runtime host component in the
  approved template (:1322), which collides with the pinned "no remote-target param" invariant (:47-49,
  spec-025:92, ARCH S4). Do **not** ship the detection-side half alone — it manufactures dead approved
  probes. Its own S4-surface decision.
- **Stale-record retirement (spec-036).** A pre-fix mislabeled `http app monitor` recipe/item lingers
  after this fix until 036's retire/suppress story lands.
- **Genuinely ambiguous two-HTTP PIDs.** A PID whose two ports both answer `GET /` with no actuator and
  no config hint (API + admin UI, gRPC-gateway pairs) is not merged — the confidence gate falls back to
  per-port emission by design.
