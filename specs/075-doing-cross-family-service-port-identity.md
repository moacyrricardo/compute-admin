Status: doing
Branch: moacyrricardo/bol-905-cpt-cross-family-service-port-identity-dedup-spec-075
Issue:  BOL-905

# 075 — Cross-family service-port identity & dedup

**Graduated from concern [074](074-concern-discovery-signal-to-noise.md) (issues A + B).** Decides
the leaning recorded there: **A1 + B2**. Issues C (systemd flood) and D (monitor enabled-only) stay
open in 074, delegated to concerns 036 and 040 respectively.

## Context

The first **real-MINA** discovery run (a live host, login user without sudo — 074's evidence)
surfaced a well-known service, nginx, wrongly:

- nginx `:80` and `:443` arrived as **two anonymous** `app-80` / `app-443` records — `contextKey=null`,
  confidence `low`, note *"unattributed listener · owner unreadable"*. nginx workers run as
  `www-data`/root; discovery logs in as the login user with no sudo, so `/proc/<pid>` is unreadable →
  no process attribution → spec-062's degrade path emits **one anonymous `app-<port>` per port**
  (`AppMonitorDiscoverer.java:394`). Two ports, two ids, and spec-066's `groupByContext` has no shared
  context to collapse them under. **(074 issue A.)**
- nginx is also **double-surfaced**: the dedicated `NginxDiscoverer` (NGINX family) emits a correct
  `nginx` recipe, *and* the two anonymous ports appear under the app-monitor's generic recipe. The
  discoverers run independently with **no shared claimed-port registry**
  (`DiscoveryService.java:189-201`); app-monitor's `claimedKeys` dedup is intra-pass only. **(074
  issue B.)**

`ServiceCatalog` already knows `nginx → port 80` (and postgres/mysql/mariadb defaults) but only exposes
`fingerprintByProcess` — which needs the process name we don't have when `/proc` is unreadable. There is
no port-based fallback.

## Decision

1. **A1 — port-based service fingerprint fallback.** Add `ServiceCatalog.fingerprintByPort(int port)`
   returning the catalog `Service` for a well-known listening port (`80`/`443` → nginx, `3306` → mysql,
   `5432` → postgres). In `AppMonitorDiscoverer`, on the **unattributed** listener path only (before the
   GENERIC `app-<port>` degrade at `:394`), apply it: the matched service supplies the record's identity
   (`appName = service.name`) and its **catalog context** (config/data dir, via the existing
   `resolveContextForDir`), exactly like the process-fingerprint branch — but at **`low` confidence**
   (a port guess, never `high`). `22` (ssh) and `53` (systemd-resolved/dns) are **recognised-and-skipped**
   — infrastructure, not apps. Effect: nginx `:80` and `:443` both resolve to `nginx` with the **same
   `contextKey`** (the nginx config/data dir), so spec-066 collapses them into **one nginx context card**.
   An *attributed* listener keeps its real process fingerprint — A1 never overrides a readable process.

2. **B2 — post-pass cross-family reconciliation.** After every discoverer has run, in
   `DiscoveryService.persist(...)`, fold a fingerprinted well-known service under its **typed family
   recipe** when one exists for the machine: if a `NGINX`/`DATABASE` recipe is present in the same pass,
   move the A1-identified `(addr,port)` items onto **that** recipe's `app_port_list` and **drop** the
   duplicate entries from the app-monitor generic recipe. A well-known service is then represented
   **once** — under its own family recipe, now carrying its listening ports — instead of twice. Operates
   on the accumulated proposal set (order-independent), not on discoverer ordering.

3. **Gate/confidence boundary.** A port-derived identity is **presentation/grouping only**: it is `low`
   confidence, never feeds a gated action name, and never enters `ActionSnapshot.hash` (ARCH S4 /
   spec-055 secret rules). It changes which card a port renders under; it changes nothing the gate hashes.

## Implementation

- **`ServiceCatalog`** — add `static Service fingerprintByPort(int port)` over the existing `ROWS`
  (matching `defaultPort`; nginx additionally owns `443`). Keep it a pure lookup; no new rows required
  beyond teaching nginx its `443` alias. A small skip-set (`22`, `53`) lives in the discoverer, not the
  catalog (they are non-app infrastructure, not catalog services).
- **`AppMonitorDiscoverer`** — in the unattributed branch (`~:390-397`): before emitting the GENERIC
  degrade, try `fingerprintByPort(u.port())`; on a hit (and not in the skip-set), emit a `Resolved` with
  the service identity + `resolveContextForDir(catalog dir)` + `confidence="low"` instead of the
  anonymous `app-<port>`. Skip `22`/`53` entirely (emit nothing). Reuses the existing service-fingerprint
  plumbing already used for the *attributed* path (`:352-358`).
- **`DiscoveryService.persist`** — after building the proposal list, before/within the persist
  transaction: index typed recipes (NGINX/DATABASE) proposed this pass by family; for each app-monitor
  generic item whose fingerprinted service maps to a present typed family, relocate the item to that
  recipe's `app_port_list` and remove it from the generic recipe. Idempotent under spec-021 re-discovery
  (the app_port_list is refreshed in place, `:248-255`, not part of any action hash).
- **Tests** — unit: `fingerprintByPort` table (80/443→nginx, 3306→mysql, 5432→postgres, 8090→none);
  discoverer: an unattributed `:80`+`:443` pair on an unreadable owner yields one nginx-context pair
  (not two `app-<port>`), `:22`/`:53` yield nothing; reconciliation: with a NGINX recipe present, the
  nginx ports land on it and are absent from the generic recipe. A spec-066 render-check that the two
  nginx ports collapse to one context card.

## Known Gaps

- **NginxDiscoverer stays config-based.** It still doesn't itself enumerate live ports; B2 supplies them
  by relocation. Teaching `NginxDiscoverer` to read `nginx -T` `listen` directives directly is a possible
  future refinement, not required here.
- **Skip-set is minimal** (`22`, `53`). Other infrastructure ports (e.g. `25`, `123`) aren't special-cased;
  they remain anonymous `app-<port>` records until 074-C (the systemd/flooding relevance gate, → 036)
  addresses non-app noise broadly. This spec deliberately fixes only the **catalog-known** services.
- **A well-known port running something else** (rare — e.g. a custom app squatting `:80`) would be
  mislabelled nginx at `low` confidence. Acceptable: low confidence signals the guess, and an *attributed*
  process always wins over the port fallback.
- **Orthogonal to spec-073** (management-port/actuator merge) and to 074 issues **C** (systemd flood →
  036) and **D** (monitor shows only enabled → 040) — none are in scope here.
