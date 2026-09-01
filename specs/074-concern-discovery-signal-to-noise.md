# 074 — Discovery signal-to-noise & cross-family identity

**Status:** concern (partially resolved) · surfaced by the first **real-MINA** discovery run
(spec-066/073 validation against a live host), which exposed discovery-quality problems the canned
`demo` fleet (`CannedSshExecutor` / `DemoFleet`) structurally cannot show.

> **Issues A + B graduated → spec [075](075-todo-cross-family-service-port-identity.md)** (decided
> A1 + B2). **C** (systemd flood) stays delegated to concern **036**; **D** (monitor shows only
> enabled) to concern **040**; **E** (enablement friction) is answered below (UX-only). The concern
> stays open as the record for C/D/E.

## Problem

Every prior discovery exercise ran against the **demo profile**, whose `@Primary CannedSshExecutor`
answers all SSH from `DemoFleet` fixtures — and `DemoFleet.ssListeners()` only has rows for the two
fake hosts (`10.10.0.11`, `10.10.0.12`). The first discovery against a **real** host (`boletim`,
Ubuntu 24.04, login user `ubuntu`, no sudo; a `java -jar boletim-4.18.0.jar` app + nginx + mysql)
produced a single `generic app monitor` recipe with a **27-item `app_port_list`** dominated by noise:

| what | example items | note |
|---|---|---|
| systemd OS units swept as port-0 "apps" (19) | `ModemManager`, `multipathd`, `polkit`, `udisks2`, `getty-tty1`, `dbus`, `rsyslog`, `unattended-upgrades`, `systemd-*` | non-listening sweep (spec-056) has no "is this an app" gate |
| well-known ports as **anonymous** per-port records | `app-80`, `app-443` (nginx), `app-22` (sshd), `app-53` (systemd-resolved) | *"unattributed listener · owner unreadable"* |
| **double-surfaced** service | `nginx` (NGINX-family recipe) **and** `app-80`/`app-443` | same service, two representations |

For contrast, the app *did* resolve correctly: `boletim-4.18.0` on `:8090`, context
`/home/ubuntu/app/boletim` (a readable app-folder) — so the machinery works when `/proc` is
readable. The problems below are all about the **unreadable / OS-owned** majority.

Four distinct issues (A–D). **A and B are new**; C and D are largely owned by existing concerns and
are recorded here only with the fresh evidence + the specific new angle.

## Hypotheses / Options

### A — Well-known service ports don't share an identity (nginx `:80` + `:443` = two anonymous apps)

**Root.** nginx workers run as `www-data`/root; discovery logs in as `ubuntu` with no sudo, so
`/proc/<pid>` is unreadable → no process attribution → spec-062's degrade path emits **one anonymous
`app-<port>` per port** (`AppMonitorDiscoverer.java:394`), `contextKey=null`, confidence `low`. Two
ports ⇒ two ids, and with no shared process/context there is nothing for `groupByContext` (spec-066)
to collapse them under. `ServiceCatalog` knows `nginx → 80` but only exposes `fingerprintByProcess`
(needs the process name we don't have); there is **no port-based fallback**.

- **A1 — `ServiceCatalog.fingerprintByPort(port)` fallback.** Well-known ports resolve to a service
  identity + its catalog context even when `/proc` is unreadable (`80`/`443` → nginx → `/etc/nginx`
  or `/var/www`; `3306` → mysql; `5432` → postgres; `22`/`53` → recognised-and-skipped). `:80` and
  `:443` then share one nginx context ⇒ one card. *Con:* a port guess is weaker evidence — must be
  labelled `low`/`medium` confidence and **never** promoted into a gated identity (S4 / spec-055).
- **A2 — NGINX discoverer owns its ports.** `NginxDiscoverer` reads `nginx -T`/config `listen`
  directives and populates its recipe's `app_port_list` with `80`/`443`; app-monitor then **skips**
  a port a typed recipe already owns (see B). *Con:* config parsing + cross-family coordination.
- **A3 — group the unattributed remainder into one "unattributed listeners" card** without trying to
  name it. Cheapest, but keeps `nginx` mystery-shaped; weakest.

### B — Cross-family duplication (nginx surfaced by the NGINX recipe **and** by app-monitor)

**Root.** `DiscoveryService` runs each discoverer independently with **no shared claimed-port
registry** (`DiscoveryService.java:189-201`); app-monitor's `claimedKeys` dedup is intra-pass only.
So a port a typed family (NGINX / DATABASE) already recognises still reappears as an anonymous
`app-<port>`.

- **B1 — shared claimed-port set threaded through the pass.** A typed discoverer registers its ports;
  app-monitor skips them. *Con:* couples to discoverer ordering.
- **B2 — post-pass reconciliation in `persist()`.** Drop app-monitor GENERIC records whose
  `(addr,port)` is owned by a typed recipe from the same pass. Order-independent, cheaper.
- **B3 — status quo.** Document that a service can appear both under its family recipe and as an
  anonymous app card. (This *is* the noise flagged.)

### C — Every systemd unit swept in as an "app" (19 of 27 items)

Largely **already framed by concern [036](036-todo-recipe-param-discovery-lifecycle.md)**
("discoverers **flood** — all systemd units / all containers"; no suppress/hide/retire). Recorded
here with the boletim evidence. **New angle to fold into 036:** a *relevance gate* on the
non-listening sweep (spec-056) — e.g. only sweep units whose fragment path is under
`/etc/systemd/system` (admin-installed) rather than `/lib/systemd/system` (OS vendor), or an
allow/deny list. Cross-ref spec-056 (the sweep) + 036 (suppression/retirement).

### D — Monitor should display only *enabled* apps

User request: the Monitor / consumer list shows everything discovered; it should show only apps that
are **enabled / opted-in**. Overlaps [040](040-todo-monitor-runtime-view-and-model-weight.md) (monitor as a runtime
view) and 036 (suppress). Options: **D1** filter monitor consumers to those whose backing recipe has
an APPROVED action; **D2** an explicit per-app "monitor this" opt-in (ties to 053's declared apps);
**D3** a UI-only filter toggle (cheapest, no model change). Cross-ref 040 / 036 / spec-066 surface-2
(native consumers per context).

## Open Questions

- **(E) Enablement friction — ANSWERED: UX-only.** User reported "a lot of things I had to enable."
  Verified: **6 of 7 families default to `true`** (`DiscovererFamily.java` — Nginx/Database/Cron/
  Systemd/Host/App); **only Docker defaults to `false`** (deliberately — "root-equivalent"). A fresh
  machine's `familyStates` applies those defaults (`DiscoveryEnablementService.java:66`), so discovery
  already runs the six by default and clicking an already-on family is a **no-op** (the boletim DB rows
  were self-toggles). So this is **not** a functional requirement — it's a presentation issue: every
  family renders as an identical clickable `tag--filter` chip (`app.js:1222`), so the bar reads as
  "enable each before discovering." **Fix direction (UX-only, small):** visually distinguish the single
  **opt-in** family (Docker, which carries a `note`) from the default-on informational ones, and/or add
  copy that discovery runs enabled families by default and only Docker needs turning on. Candidate for a
  small UX spec; not blocking.
- **A vs B priority.** A1 fixes the *symptom* (identity) cheaply; B fixes the *duplication*
  structurally. They compose. **Leaning: A1 + B2** — the smallest correct combination: A1 gives
  `:80`/`:443` an nginx identity, B2 removes the anonymous copies.
- **Gating.** A port-based identity must stay presentation/grouping only — never feed a gated action
  name, never enter `ActionSnapshot.hash` (S4 / spec-055 secret rules). Confirm.
- **Context reconciliation.** Does A1's nginx context (`/etc/nginx` or `/var/www`) agree with the
  NGINX recipe's own context so the card and the recipe don't disagree?

## Cross-references

Builds on the real-MINA validation of spec-066 (context-grouped discovery) and spec-073
(management-port merge). Inverse-multiplicity sibling of **062** (unattributed listener → `app-<port>`
— A is that path meeting a *known* service). Delegates C → **036**, D → **040/036**. Touches
spec-055 (context/identity), spec-056 (non-listening sweep), and `ServiceCatalog`.
