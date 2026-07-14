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

### 1 · Exceptions → the web layer: delete the 19 mappers

**Current state — a split-brain error model.** Two patterns coexist for turning a
service failure into an HTTP error:

- **404 / 409 / 401 / 502** (18 codes): a custom exception in `service/` that
  `extends RuntimeException`, **plus** a dedicated `*ExceptionMapper` `@Provider` in
  `common/` doing `Response.status(X).entity(Map.of("error","<code>")).build()`. That is
  the **19 mappers, 521 lines**. Status spread: **8× `NOT_FOUND`, 8× `CONFLICT`, 1×
  `UNAUTHORIZED`, 1× `BAD_REQUEST`, 1× `BAD_GATEWAY`**. 18 are byte-for-byte uniform; the
  outlier is `SshExecutionExceptionMapper` (adds a `detail` field, 502, with a rationale).
- **400 Bad Request** (validation): services `throw new jakarta.ws.rs.BadRequestException(…)`
  **directly** — **59 call sites across 7 services** — with **no** custom exception and
  **no** mapper (RESTEasy maps the JAX-RS built-in for free).

So `service/` **already imports `jakarta.ws.rs`** (7 files) and already throws web-layer
exceptions. ARCH.md's `api → service` direction says service shouldn't depend on the web
layer — but that boundary is **already crossed** for the 400 path, and **no ArchTest
enforces it** (only the Gate/MachineEvent ArchTests exist). The 19-mapper tax exists only
because the *other* statuses took the custom-exception route instead of the
`BadRequestException` route. **The real decision is which of the two existing patterns
wins** — not whether to have mappers (both options below delete ~18 of them).

---

**Option 1 — exceptions carry their own `Response` (extend `WebApplicationException`).**
Finish the pattern the 400 path already uses. Each custom exception extends
`WebApplicationException` (directly, *A*) or a shared base `AppException` holding
`(Status, errorCode)` (*B*), building its body in the constructor so RESTEasy returns it
with **no mapper**:

```java
// base (option B)
public class AppException extends WebApplicationException {
  public AppException(Response.Status s, String code) {
    super(Response.status(s).entity(new ErrorResponse(code)).build());
  }
}
// each of the 19 collapses to one line of intent:
public class MachineNotFoundException extends AppException {
  public MachineNotFoundException(String id) { super(NOT_FOUND, "machine_not_found"); }
}
```

- **Deletes all 19 mappers.** `SshExecutionException` becomes `super(BAD_GATEWAY, …)` with
  its `detail` (an `ErrorResponse(String error, String detail)` or a 1-off subclass).
- **Consistency:** matches the 59 existing `BadRequestException` throws (both are
  `WebApplicationException`); the 400 path *could* also fold into
  `ParamValidationException extends AppException(BAD_REQUEST, "param_validation_failed")` so
  every error flows one way.
- **Layering:** pushes `jakarta.ws.rs` into more `service/` classes — but service already
  imports it (7 files today), so this **deepens an existing crossing**, it doesn't create a
  new one. If a clean service↔web boundary is ever wanted, this is the wrong direction.
- **Impact:** −19 mapper classes (~521 lines); +`AppException` +`ErrorResponse` (~30);
  modify 19 exceptions to extend the base with a 1-line super (~+40); mapper-asserting tests
  move to asserting the endpoint status/body. **Net ≈ −18 classes, ~−450 lines.** Effort:
  **low**, purely mechanical.

**Option 2 — keep `service/` web-agnostic; one generic mapper.** The principled-layering
direction: an exception knows a *status intent* + code but not JAX-RS. A single
`AppExceptionMapper` reads a marker and builds the one `Response`:

```java
public interface HasError { int status(); String code(); }   // plain int → no ws.rs in service/
@Provider @Component
class AppExceptionMapper implements ExceptionMapper<RuntimeException> {
  public Response toResponse(RuntimeException e) {
    if (e instanceof HasError h) return Response.status(h.status()).entity(new ErrorResponse(h.code())).build();
    throw e; // let anything else fall through to the container's 500
  }
}
```

- **Deletes 18 of 19 mappers** (keeps this one generic mapper; `SshExecutionException`'s
  `detail` handled by an optional-detail method on the marker, or its own mapper).
- **The consistency catch:** to be *actually* principled, this also means unwinding the
  **59 `BadRequestException` throws** into web-agnostic exceptions — otherwise `service/`
  still imports `ws.rs` and the layering goal isn't met, leaving option 2's only real gain
  over option 1 as "1 mapper instead of 0" while exceptions stay ws.rs-free (a marginal
  purity win). Unwinding the 400 path is a **much larger, riskier** change (7 services, 59
  sites) than the 19 mappers.
- **Layering:** the intended-clean direction — *iff* the `BadRequestException` throws are
  also converted; otherwise mostly cosmetic.
- **Impact (mappers only):** −18 mapper classes; +1 mapper +1 marker +`ErrorResponse`
  (~45); modify 19 exceptions to implement the marker (~+40). **Net ≈ −16 classes, ~−440
  lines.** *To be principled*, add: unwind 59 `BadRequestException` sites → new/curated
  exceptions + modify 7 services. Effort: **low** for the mappers, **moderate-high** if the
  400 path is included.

**Deciding table** — both delete ~18 mappers; the choice is the *layering stance*:

| | Option 1 (extend WAE) | Option 2 (web-agnostic + 1 mapper) |
|---|---|---|
| Mapper classes left | **0** | **1** (generic) |
| `service/` ↔ `ws.rs` | deepens the existing crossing | clean **only if** the 59 `BadRequestException` throws are also unwound |
| Fits today's 400 path | **matches it** | **contradicts it** (unless the 400 path is converted too) |
| Effort | low | low (mappers) → **moderate-high** (to be principled) |
| Net classes / lines | ≈ **−18 / −450** | ≈ −16 / −440 (mappers only), more if the 400 path is included |

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
| 1 | exceptions → web layer (opt 1) / generic mapper (opt 2) | **−16 to −18** | **~−440 to −450** | low (behaviour identical; the `service/`↔web boundary is *already* crossed, so opt 1 adds no new smell; opt 2 is only "clean" if the 400 path is also unwound) |
| 2 | DTOs over `Response`/`Request` | +1 (`ErrorResponse`) | ~+5 | none — already done |
| 3 | yaml → properties | 0 | ~net-neutral (±15) | low (check nested lists/profile groups) |
| 4 | uber jar + release + README | +1 (workflow) | ~+70–100 | low |

**Total ≈ −16 net classes, ~−350 net lines**, dominated by §1.

## Recommendation (leaning — this stays a concern; the call is yours)

- **§1 → Option 1 (extend `WebApplicationException`), variant B (a shared `AppException`
  base).** Option 2's layering purity is *illusory* until the 59 `BadRequestException`
  throws are also unwound — a much larger change this cleanup should not smuggle in.
  Option 1 is consistent with what the code already does at 400, deletes **all 19** mappers
  (0 left), and is the lowest-effort path. If a genuinely clean `service/`↔web boundary is
  ever wanted, make it its **own** architectural spec (unwind *all* `ws.rs` from `service/`,
  the 400 path included) — not this one.
- **§2 → close it.** Already satisfied; its only artifact is the `ErrorResponse` record,
  which rides along inside §1. No standalone work.
- **§3 → do it, lowest priority.** Pure preference, net-neutral, mechanical — the smallest
  ROI of the four. Quick win; verify no profile-group / nested-list YAML first.
- **§4 → do it, highest external value.** The jar already builds; a release workflow + a
  README "Download & run" is the only thing between the project and a `java -jar` download.
  Recommend: on a `v*` tag, publish a GitHub Release with the jar **+ a checksum**.

## Suggested derivative specs

Split into **three** specs (not four — §2 folds into §1). Numbers assigned at authoring
time (`/new-spec`); suggested slugs:

1. **`unified-error-model`** (§1 + §2) — *the real refactor.* Add `AppException(Status,
   code)` + `ErrorResponse`; convert the 19 exceptions to the base; **delete the 19
   mappers**; preserve `SshExecutionException`'s `detail`. Optional stretch: fold the 59
   `BadRequestException` throws into a `ParamValidationException` for one uniform path.
   **≈ −18 classes / −450 lines.** Size **M** — many files, but mechanical; zero behaviour
   change (test: identical statuses/bodies before/after).
2. **`config-to-properties`** (§3) — convert the 3 YAML files to `.properties`. Size **XS**.
   Independent.
3. **`release-pipeline`** (§4) — `release.yml` on `v*` (build → GitHub Release with jar +
   checksum), pin `<finalName>`, README "Download & run". Size **S**. Independent; highest
   user-facing value.

All three are **independent / parallelizable**. Suggested order by value: **§4 release
first** (unblocks distribution) → **§1** (the substantive cleanup) → **§3** last
(cosmetic). A deliberately-separate, larger **`service-web-boundary`** spec is the home for
Option 2's full ambition if the team ever wants it — explicitly out of scope for these
three.

## Open Questions

1. **§1 — the layering stance (the real decision).** ARCH.md says `api → service`, yet
   `service/` **already** imports `jakarta.ws.rs` and throws `BadRequestException` at 59
   sites, and **no ArchTest enforces** the boundary. So the question isn't "is ws.rs allowed
   in service?" (it already is) — it's: **embrace it** (option 1: extend WAE, delete all 19
   mappers, consistent with the 400 path) **or reverse it** (option 2: web-agnostic
   exceptions + one generic mapper, which to be principled also means unwinding the 59
   `BadRequestException` throws — a much bigger change)?
2. **§1 completeness — confirmed:** 18 of 19 mappers are status + `{"error":code}` only;
   the lone outlier is `SshExecutionExceptionMapper` (adds a `detail` field, 502, with a
   documented rationale) — either option handles it as a one-off. No logging/headers/i18n
   in any mapper.
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
