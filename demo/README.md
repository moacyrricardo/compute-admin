# compute-admin — demo & GIF recordings

A **re-runnable** harness that boots compute-admin against a **fake fleet** (no real
SSH targets) and records short GIFs of the core flows. Nothing here is part of the app
build — it is dev/demo tooling. **It is not wired up or validated yet** (see
[Status](#status)); this document + [`fake-fleet.md`](./fake-fleet.md) +
[`steps.md`](./steps.md) are the plan and the maintenance contract.

## What we record (3 flows × 2 viewports = 6 GIFs)

Every flow is recorded at **both** viewports — desktop and mobile — so the same steps
must work in both layouts (the mobile pass exercises the spec-043 responsive UI: the
**Menu** nav toggle, single-column card stacking, and the **bottom-sheet** consumer
drawer). Outputs are named `NN-slug.desktop.gif` / `NN-slug.mobile.gif`.

| # | Flow (→ `.desktop.gif` + `.mobile.gif`) | Shows | Steps |
|---|-----|-------|-------|
| 1 | `01-add-machine-discover` | Register a machine, then **Discover recipes** → proposed monitor/app/docker recipes appear | [steps.md §1](./steps.md) |
| 2 | `02-enable-monitor` | Enable the discoverer families + **approve** the monitor recipes/actions through the gate | [steps.md §2](./steps.md) |
| 3 | `03-monitor-fleet` | The **Monitor** view: 2 machines, **>1 app each**, and a **shared database on each** — one **docker**, one **native** — on the RAM/CPU/disk axes; open a consumer drawer | [steps.md §3](./steps.md) |

**Viewports** (set by the recorder, see [`record.py`](./record.py)):
- **desktop** — 1280×800.
- **mobile** — 390×844 (iPhone-class), `deviceScaleFactor` 2 — below the spec-043
  `--bp-sm` (480px) breakpoint, so the Menu nav + stacked/bottom-sheet layout render.

The target end-state the recordings depict is the fake fleet defined in
[`fake-fleet.md`](./fake-fleet.md):
- **web-prod-1** — apps `checkout-api` (springboot) + `web-frontend` (generic) + a
  **docker** shared datastore (`postgres`, used by both).
- **api-prod-2** — apps `orders-api` (springboot) + `billing-worker` (generic) + a
  **native** shared datastore (native `postgres` process, used by both).

## How it runs (the pieces)

1. **App under a `demo` Spring profile** — boots the real app but swaps the MINA
   `SshExecutor` for a **canned executor** that returns scripted stdout per
   (host, command), so discovery + the monitor polls render the fake fleet
   deterministically. One machine (`api-prod-2`) is pre-seeded so GIF 3 shows two
   machines; `web-prod-1` is added live during GIF 1. **See [`fake-fleet.md`](./fake-fleet.md)
   for the profile + the full command→output contract.**
2. **Browser driver** — a real Firefox via **geckodriver**, same stack the
   `test-flow-headless` agent uses. Either:
   - invoke the **`test-flow-headless` agent** with the relevant section of
     [`steps.md`](./steps.md) as its interaction steps (it drives Firefox + returns a
     gif/screenshot), **or**
   - run the standalone [`record.py`](./record.py) fallback (geckodriver + `ffmpeg`).
   The agent does **not** start the server, seed data, or publish — the runner (below)
   does all three.
3. **GIF encoding** — `ffmpeg` turns captured frames / an `x11grab` capture into an
   optimized gif (palette pass). See `record.py`.

## Run procedure (for whoever executes it later)

```bash
# 0. prerequisites: firefox, geckodriver, ffmpeg on PATH; a fresh DB
#    (the demo profile seeds its own users/machines — do NOT point at real ./data)
# 1. boot the app with the demo profile on a dedicated port + throwaway DB dir
PORT=8099 mvn -q spring-boot:run \
  -Dspring-boot.run.profiles=demo \
  -Dspring-boot.run.arguments="--ca.db.dir=./data-demo"
# 2. wait for `Started Application`; log in with the seeded demo user (see fake-fleet.md)
# 3. record each flow at BOTH viewports — agent per steps.md §N, or:
python3 demo/record.py --steps demo/steps.md --section 1 --viewport desktop --out demo/out/01-add-machine-discover.desktop.gif
python3 demo/record.py --steps demo/steps.md --section 1 --viewport mobile  --out demo/out/01-add-machine-discover.mobile.gif
#    (…repeat for sections 2 and 3)
# 4. review the gifs in demo/out/, then commit the .gif files (or attach to the PR/README)
# 5. tear down: stop the app, `rm -rf ./data-demo`
```

## Status

**Prepared, not built/validated.** Outstanding before the first real run:
- [ ] Implement the `demo`-profile `CannedSshExecutor` + seeder per
      [`fake-fleet.md`](./fake-fleet.md) and validate its outputs against the live
      parsers (the contract table lists the exact parser for each command).
- [ ] Confirm the `--ca.db.dir`/demo-DB override exists (or add one) so the demo never
      touches real `./data`.
- [ ] First recording pass; commit the produced `demo/out/*.gif`.

## Maintenance — what to check when the app changes

The recordings depend on stable **routes, visible button/link text, and the
command→output contract**. When any of these change, update the mapped file:

| If this changes… | Update | Depends on |
|---|---|---|
| Register form fields / route `#/machines/register` | [steps.md §1](./steps.md) | `app.js` `screenRegisterMachine` |
| The **Discover recipes** button / Discovery panel | [steps.md §1–2](./steps.md) | `app.js` `discoverySection` (spec-035) |
| Approval flow / button labels (Submit/Approve/Revoke) | [steps.md §2](./steps.md) | `app.js` `screenActionDetail` / `act()` — **spec-044 will move this into a drawer + split-button; rewrite §2 then** |
| Monitor route/controls, consumer drawer, bars | [steps.md §3](./steps.md) | `app.js` monitor screen + `openConsumerDrawer` (spec-034/037/041) |
| What a discoverer/monitor probe runs, or a parser's expected format | the **contract table** in [fake-fleet.md](./fake-fleet.md) | the discoverers in `discovery/service/*` and the parsers in `app.js` |
| Nav labels / login flow | [steps.md](./steps.md) header | `index.html` nav, `app.js` login |

Keep the contract table's "source parser" column accurate — it is the single place that
tells a future maintainer *why* each canned output is shaped the way it is.
