# 046 — Unified error model (delete the 19 exception mappers)

**Status:** done · branch `moacyrricardo/spec-046-unified-error-model` · no Linear issue
(blocked; tracked as `spec-046`). Graduated from concern
[045](./045-todo-arch-cleanups.md) §1 + §2.

## Context

The HTTP error model is split-brain (researched in concern 045): **400** validation is
thrown as `jakarta.ws.rs.BadRequestException` directly in services (59 sites, 7 files),
while **404 / 409 / 401 / 400 / 502** go through **19 custom exceptions** (`extends
RuntimeException`) each paired with a dedicated **`*ExceptionMapper` `@Provider`** in
`common/` — **19 mapper classes, 521 lines**, every one just
`Response.status(X).entity(Map.of("error","<code>")).build()`. 18 are byte-uniform; the lone
outlier is `SshExecutionExceptionMapper` (502 + a `detail` field). `service/` already
depends on `jakarta.ws.rs` and no ArchTest forbids it.

Concern 045 chose **Option 1**: let exceptions carry their own `Response` and delete every
mapper — consistent with the existing 400 path, lowest effort, zero behaviour change.

## Decision

Introduce a shared `common/AppException extends WebApplicationException` that builds the
error `Response` in its constructor, convert the 19 custom exceptions to extend it, and
**delete all 19 `*ExceptionMapper` classes**. Error bodies become a typed `ErrorResponse`
record (resolves 045 §2's "generic `Map.of`"). **Wire format is unchanged** — same HTTP
status and same `{"error":"<code>"}` (plus `{"detail":…}` for the SSH case) for every path.

Explicitly **out of scope**: unwinding the 59 direct `BadRequestException` throws (Option 2
/ a clean `service/`↔web boundary is deferred to a separate future spec).

## Implementation

- **`common/ErrorResponse.java`** — `record ErrorResponse(String error, String detail)` with
  `@JsonInclude(NON_NULL)` so `detail` is omitted when null. One-arg factory `of(String
  error)`.
- **`common/AppException.java`** — `extends WebApplicationException`; constructors
  `AppException(Response.Status status, String code)` and `(status, code, String detail)`,
  each calling `super(Response.status(status).entity(new ErrorResponse(code, detail)).build())`.
- **Convert the 19 exceptions** (in their existing `*/service/` + `ssh/` + `common/`
  packages) to `extends AppException`, moving the status + code into a one-line `super(...)`:
  - 8× `NOT_FOUND` (`machine_not_found`, `recipe_not_found`, `action_not_found`,
    `run_not_found`, `token_not_found`, `pairing_not_found`, `blueprint_not_found`,
    `blueprint_action_not_found`)
  - 8× `CONFLICT` (`machine_already_registered`, `machine_name_taken`, `email_taken`,
    `illegal_approval_transition`, `action_not_approved`, `action_modified`,
    `script_modified`, `script_unreadable`)
  - 1× `UNAUTHORIZED` (`unauthorized`), 1× `BAD_REQUEST` (`param_validation_failed`),
    1× `BAD_GATEWAY` (`SshExecutionException` → `super(BAD_GATEWAY, "ssh_failed",
    exception message as detail)`).
- **Delete** all 19 `src/main/java/com/iskeru/computeadmin/common/*ExceptionMapper.java`.
- **Tests:** endpoint/integration tests asserting a status + `{"error":code}` are unaffected
  (identical wire output). Any test that referenced a `*ExceptionMapper` class directly (or
  a `common` package assertion) is updated/removed. Add a small parametric test asserting
  each `AppException` subclass yields its expected status + code. `mvn test` green; gate +
  existing ArchTests untouched.

## Known Gaps

- **The 59 `BadRequestException` direct throws stay as-is** (uniform JAX-RS 400). Folding
  them into a `ParamValidationException`-style single path is deferred; not required for the
  mapper deletion.
- **No clean `service/`↔web boundary** — exceptions still (transitively) import
  `jakarta.ws.rs` via `AppException`. Removing `ws.rs` from `service/` entirely is a
  separate, larger `service-web-boundary` spec (concern 045, Option 2).
- Wire format intentionally unchanged (no RFC-7807/problem+json migration here).

## How the implementation differed

Faithful to the decision. Two refinements worth recording:

- **A third `AppException` constructor.** Beyond the spec's `(status, code)` and
  `(status, code, detail)`, a message-first `(String message, status, code)` was
  added so `UnauthorizedException` keeps its internal reason ("Invalid email or
  password", "Missing JWT", …) on `getMessage()` **without** leaking it to the wire
  — the 401 body stays `{"error":"unauthorized"}`. This preserves the existing
  `AuthServiceTest` message assertions and the anti-enumeration property. The SSH
  `(status, code, detail)` path already sets the message to the detail, so run-output
  capture (which reads `getMessage()`) is unchanged.
- **`SshExecutionException` no longer chains its cause.** `AppException`'s
  constructors take no `Throwable`, so the wrapped `IOException` cause is dropped.
  Nothing consumed the cause (the run path reads only `getMessage()`; no test
  asserts it), and the 502 wire body + message are identical.

Converted 19 exceptions, deleted 19 `*ExceptionMapper` classes, added the typed
`ErrorResponse` and `AppException` plus a parametric `AppExceptionTest`. Full suite
green (283 tests); `grep "implements ExceptionMapper"` → 0. The 59 direct
`BadRequestException` throws stay as-is (out of scope).

## Related

concern [045](./045-todo-arch-cleanups.md) (§1 + §2), ARCH.md (layering), spec-004 (gate —
untouched).
