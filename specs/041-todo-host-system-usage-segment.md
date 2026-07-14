# 041 — Host system/other usage segment (real RAM/CPU/disk on app-less machines)

**Status:** todo · no Linear issue (blocked for this repo; tracked as `spec-041`).

## Context

The fleet monitor (spec-034) renders each machine as segmented tri-axis bars where
every segment is an attributed *consumer* (an app or datastore), and whatever is
unattributed is drawn as a hatched **"free remainder."** The host vitals from the
`monitor machine` recipe (spec-023: `free -m`, `top -bn1`, `df -h`) are polled **only
for their totals**, which serve as the %-of-host denominators — the actual *usage* is
discarded:

- `pollHostTotal` (app.js) keeps `mem.total` and drops `mem.used`, even though
  `parseMem` already computes `used`/`pct`.
- Only `nproc` (core count) is polled for CPU; the host's actual CPU-in-use
  (`top -bn1`) is not polled at all — the host CPU/disk parsers were removed in
  spec-034 when the host panel became per-consumer bars.
- Only the `df` *total* is kept for disk, not used.

Consequences:

1. A machine with **no registered apps** shows empty bars and *"No discovered consumers
   on this host"* — it looks idle even at 90% RAM.
2. Even with apps, the OS and everything unattributed reads as "free," never as real
   consumption.
3. The `SYSTEM` bucket exists in the model (the "system / free remainder" chip) but its
   axes are never filled, so revealing it shows nothing.

This is the concrete symptom behind concern spec-040: the monitor never surfaces true
machine usage, only per-app attribution.

## Decision

Surface an **"other / system"** segment carrying the machine's **actual unattributed
usage**, so each axis bar reads:

```
[ apps / datastores … ][ other/system = host_used − Σ attributed ][ free = total − used ]  (hatched)
```

- Poll the host vitals for their **used** values (RAM used, CPU-in-use, disk used), not
  only totals.
- Compute `other = clamp(host_used − Σ attributed_consumers, ≥ 0)` per axis and render
  it as an OTHER/system segment, filling the existing `SYSTEM` bucket (`Bucket.SYSTEM`,
  `ConsumerRole.OTHER`).
- Show it **by default** (not hidden behind the "system / free remainder" chip), or at
  minimum always when a machine has zero apps — so a bare box shows real usage.
- Keep the genuinely-free portion (`total − used`) as the hatched remainder. The bar now
  distinguishes *unattributed-but-used* from *actually-free*.

Consistent with spec-040's leaning (heavy lifting in the UI): this is client-side
parsing + a computed segment, **no backend model change**.

## Implementation

- **Client host-vitals poll (`app.js`).** Extend the host-denominator polls to also
  return `used`:
  - RAM: `pollHostTotal` → also surface `mem.used` (already parsed in `parseMem`).
  - CPU: re-add a `top -bn1` host-usage parser; poll the approved host CPU vital. The
    app-axis denominator stays `nproc`; the host CPU-used feeds only the OTHER
    computation.
  - Disk: re-add a `df` used parser; poll the approved host disk vital for used bytes.
- **Compute the OTHER segment** in `refresh()`/`paint()`: for each axis,
  `other = clamp(host_used_pct − Σ consumer_pct, 0, 100)`; attach it to the SYSTEM
  bucket consumer's `ram`/`cpu`/`disk`.
- **Render**: include the SYSTEM/other segment in `bars` by default in `paint()` (adjust
  `revealedBuckets` / the default `showSystem`), with its own categorical colour + legend
  entry; the drawer labels it "unattributed system usage (approximate)."
- **Honesty**: `free`'s `used` (buffers/cache) and summed app RSS do not reconcile
  exactly — clamp at zero, label OTHER as the approximate unattributed remainder, never a
  precise figure. If a host vital is not approved/available, that axis degrades to `—`
  (the existing honesty rule) and OTHER is omitted rather than shown as a bogus 0.

## Known Gaps

- **Approximation.** OTHER absorbs the RSS-vs-`free` slop and any double-counting; it is
  an estimate, not an accounting reconciliation. Documented, not fixed.
- **Requires the `monitor machine` host vitals approved.** With no host vital approved,
  the axis still shows `—`. This spec surfaces usage where the vitals exist; it does not
  auto-approve them (the gate is untouched).
- **Loosely coupled to spec-040.** The fix is UI-local and ships independently; if 040
  later moves bucket assembly to a transient/BFF path, the OTHER computation moves with
  it.

## Acceptance

Register a machine with **no apps**, approve its `monitor machine` vitals, open Monitor:
the RAM/CPU/disk bars show a non-empty **other/system** segment reflecting real usage
(not "No discovered consumers on this host"), with the genuinely-free tail hatched.

## Related

- spec-040 (monitor runtime view & model weight) — this is the concrete "show real
  usage / OTHER segment" example of that concern.
- spec-034 (fleet monitor UI — the segmented bars + buckets), spec-023
  (`monitor machine` host vitals), spec-032 (`Bucket` / `ConsumerRole.OTHER`).
