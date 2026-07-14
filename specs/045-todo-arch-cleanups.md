# 045 — Architecture cleanups (JAX-RS ergonomics, config format, packaging & release)

> **Concern** — options open, no decision. Four independent, mostly-mechanical cleanups
> found on a code read. **None changes runtime behaviour or the approval gate.** Grouped
> so they can be triaged/sequenced together; each could graduate to its own small spec.
> Impact figures below are researched against `main` (spec-044), not guesses.

## Problem

Boilerplate and format friction that accreted as the app grew: a 1:1 exception→mapper
tax, a couple of residual generic response bodies, YAML config where the team prefers
properties, and no download/release path for the (already-built) executable jar.

---

### 1 · Exceptions → `WebApplicationException` (kill the mappers)

`common/` holds **19 `*ExceptionMapper` `@Provider` classes (521 lines)**, each a 1:1 map
of a service exception to `Response.status(X).entity(Map.of("error","<code>")).build()`.
The **19** matching exceptions all `extends RuntimeException`; **none** extends
`WebApplicationException`. Status + error-code are the only per-exception variation — the
mapper class itself is pure boilerplate.

**Options**
- **A** — each exception `extends WebApplicationException` and builds its `Response`
  (status + `{"error":code}`) in its constructor; RESTEasy returns it directly ⇒ **delete
  all 19 mappers**. Con: exceptions gain a `jakarta.ws.rs` dependency — couples `service/`
  to the web layer (a layering smell; an ArchTest may forbid it).
- **B** — a small base `AppException extends WebApplicationException` (status + errorCode
  fields) that the 19 subclass; one place builds the body. Same 19-mapper delete, less
  duplication, same layering coupling.
- **C** — keep exceptions web-agnostic; replace the 19 mappers with **one** generic mapper
  that reads a `@ResponseStatus`-style annotation or a `HasHttpStatus` marker interface on
  the exception. Keeps `service/` free of `ws.rs`; deletes 18 of 19 mapper classes.

**Impact** — A/B: remove ~19 classes / ~521 lines; modify 19 exceptions (+3–6 lines each,
~+80–115); add 0–1 base class → **≈ −18 classes, ≈ −400 lines**. C: remove 19 mappers, add
1 mapper + 1 marker; modify 19 exceptions (+1 line each) → ≈ −17 classes, ≈ −470 lines.

### 2 · DTOs over generic `Response`/`Request` — *mostly already done*

Researched: **already satisfied** in the read/write path. All **12 `*RS`** endpoints
return typed DTO records (`RecipeDtos.ActionView`, `MachineDtos.MachineView`,
`RunDtos.RunView`, …) or `void`; **0** methods return `Response`; **no** request body is a
raw `Map`/`Object`/`JsonNode`. The only generic `Response`/`Map` usage is (a) the 19 error
mappers (§1) and (b) `AuthFilter`'s 401 `abortWith(Response…)` — a container filter, which
legitimately can't return a DTO.

**Options** — fold into §1: replace the mappers' `Map.of("error", code)` with a typed
`ErrorResponse(String error)` record so error bodies are typed too; leave `AuthFilter`.

**Impact** — **+1 record (~5 lines)**; otherwise nothing. This item is largely a
"confirmed, close it" note — the codebase already follows the rule.

### 3 · `.properties` instead of YAML config

**3 YAML files, 65 lines**: `application.yml` (41), `application-dev.yml` (8),
`application-demo.yml` (16). No `.properties` today.

**Options** — convert all three to `.properties` (flattened keys). Con: Spring lists /
nested maps and profile-group syntax are terser in YAML; `.properties` needs indexed keys
(`foo[0]=…`) which read worse — check whether any config actually uses them.

**Impact** — delete 3 `.yml`, add 3 `.properties`; ~65 → ~70–85 lines (flattening usually
adds a few). **Net classes 0.** Verify no YAML-only feature (profile groups, nested lists)
is in use first.

### 4 · Uber jar + tag-triggered release + README

`spring-boot-maven-plugin` **is** configured, so `mvn package` **already** produces an
executable fat/uber jar (`target/compute-admin-*.jar`, `java -jar`). What's missing is the
release pipeline and the docs: only `.github/workflows/tests.yml` exists, and the README
has **no** download / `java -jar` / release section.

**Options**
- Add `.github/workflows/release.yml` on a `v*` tag: build, then publish a **GitHub
  Release** with the jar attached (`gh release create` / `softprops/action-gh-release`);
  optionally pin `<finalName>` for a stable asset name.
- Add a README "Download & run" section (`java -jar compute-admin.jar`, the `PORT`/profile
  args, first-run notes — overlaps the demo run docs).

**Impact** — **+1 workflow (~40–60 lines)**; modify README (+25–40), maybe pom
`<finalName>` (+2). **Net +1 file.**

---

## Roll-up (researched estimate)

| # | Item | Classes Δ | Lines Δ | Risk |
|---|------|-----------|---------|------|
| 1 | exceptions → `WebApplicationException` | **−17 to −18** | **~−400 to −470** | low (behaviour identical; watch `service/`↔web layering) |
| 2 | DTOs over `Response`/`Request` | +1 (`ErrorResponse`) | ~+5 | none — already done |
| 3 | yaml → properties | 0 | ~net-neutral (±15) | low (check nested lists/profile groups) |
| 4 | uber jar + release + README | +1 (workflow) | ~+70–100 | low |

**Total ≈ −16 to −17 net classes, ~−320 to −390 net lines**, dominated by §1.

## Open Questions

1. **§1 layering:** does ARCH.md permit `jakarta.ws.rs` imports in `service/` exceptions
   (options A/B), or must exceptions stay web-agnostic (option C)? An ArchTest may already
   forbid the import.
2. **§1 completeness:** do any of the 19 mappers do more than status + `{"error":code}`
   (logging, headers, i18n)? (Sampled several — no — but confirm all 19 before deleting.)
3. **§3:** does any YAML config use nested lists/maps or profile-group syntax that
   `.properties` handles poorly?
4. **§4:** release on every `v*` tag or manual `workflow_dispatch`? Attach only the jar or
   also a checksum? Version from the tag or the pom?
5. **Packaging of the work:** four **separate** small specs (independent, parallelizable)
   or **one** cleanup spec? §2 is a doc-close, §3/§4 are quick, §1 is the real refactor.

## Related

- spec-004 (the approval gate — untouched by all four), **ARCH.md** (layering rules that
  decide §1's option), **CONTRIBUTING.md** (§4 changes CI), spec-035 (added
  `application-demo.yml`, §3), the `demo/` harness (§4's README overlaps its run docs).
