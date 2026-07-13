# Contributing to compute-admin

This file is **authoritative for commit/PR conventions and code style**.
[ARCH.md](./ARCH.md) owns the architecture (module boundaries, the gate, ports,
dependency direction); [specs/catalog.md](./specs/catalog.md) is the decision
catalog. When this file and a cross-project default disagree, this file wins.

## 1. Spec workflow

Every feature is a **numbered spec** under `specs/` (`NNN-status-slug.md`). Status
is `todo` / `doing` / `done`, changed by **renaming the file**. `/new-spec` authors
a new spec (or a *concern*) on `main`; the spec skills implement it. The catalog
lives in [specs/catalog.md](./specs/catalog.md).

- A **concern** is exploratory — it frames a problem and keeps options open. It is
  **not** a decision.
- A **spec** is a decision made: technically prescriptive, ready to build. When a
  concern is resolved, it graduates into a spec (or a set of specs).

## 2. Branches

Linear is **BLOCKED** for this repo, so branches carry **no issue id**. Name a
branch `<github-username>/spec-NNN-slug`. Dependent specs use **stacked branches**:
branch the child off its parent's branch, not `main`. After the parent PR merges,
**rebase the child onto `main`** and retarget its PR base to `main`.

## 3. Commits

- **One logical concern per commit**; the tree **compiles at every commit**.
- **Renames get their own isolated commit** (no content changes riding along).
- **Migrations travel in the same commit as the code they back** — never a separate
  before/after commit.
- **No unrelated cleanup** rides along.
- Subject: `spec-NNN Short description` (use `docs:` / `fix:` for non-spec work).
  The body explains intent when it isn't obvious.
- **Every commit includes a `Co-Authored-By:` trailer** naming the model that did
  the work, e.g. `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.
- **Never add a `Claude-Session:` trailer** — this is a **public** repo; don't leak
  session links.
- The **final commit on a spec branch** flips the spec file to `done` (rename),
  records the branch reference, and adds a short "how implementation differed from
  the spec" note.

## 4. Pull requests

- **One PR per spec.**
- For a **stack**, merge **bottom-up** (parent before child).
- **Never merge from an automated workflow or agent** — a human merges.
- **Never push code straight to `main`.** The one exception: `/new-spec` pushes the
  new spec file to `main`.

## 5. Migrations

Flyway owns the schema. Migrations live at `src/main/resources/db/migration/`,
named `V{n}__{snake_slug}.sql` with a **sequential integer** `V#` (no leading
zeros), **H2 dialect**, each opening with a comment naming its spec. A migration
travels in the **same commit** as the code it backs. **Never renumber a migration
that has already merged.**

## 6. Code conventions

*(inherited from birthday-rsvp)* These are the concrete idioms every spec assumes.
compute-admin **mirrors `birthday-rsvp`**; where `boletim` diverges (a `*UseCase`
layer, events-over-calls, `Long` identity ids, a published `-api` contract module),
we do **not** follow it. New code cites the spec that introduced it in a Javadoc
line (`spec-004`), exactly as birthday-rsvp cites `spec 0NN`.

**Layers.** One business layer named **`service`** — there is **no `usecase`
layer**. Responsibility is encoded in the class-name suffix, not a package:
`*Service` for business logic, plain descriptive names for pure helpers
(`SlugGenerator`-style, no suffix). Cross-module access goes **service→service or
via a port**, never into another module's repository (see ARCH.md's dependency
direction for the architectural rule).

**REST resources.** `*RS`, annotated `@Component @Path @Produces(APPLICATION_JSON)`.
They **return DTO records and throw** mapped exceptions — they never hand-build
`Response` except for streaming/binary (the SSE run-output endpoint and the
public-key resource are the only exceptions). A resource maps a request DTO into
a **service-input record** (defined in `service`), calls the service (which
returns an **entity**), and maps back with a response record's static
`of(entity)` factory. `config/JaxRsApplication` is `@Component
@ApplicationPath("/api") extends jakarta.ws.rs.core.Application`, empty body. We
**do not write Spring MVC controllers**.

**DTOs.** One **`*Dtos`** `public final class` (private ctor) per module's `api`
package, holding nested `record`s. Request records are plain; response records
own their mapping via a static `of(entity)`. **No mapper framework.**

**Persistence.** Entities in `<module>/model`: Lombok `@Getter @Setter
@NoArgsConstructor` (never `@Data`, never builders), `jakarta.persistence`
annotations, explicit `@Table`/`@Column` snake_case. **PK = app-assigned String
UUID**: `@Id @Column(length = 36) private String id = UUID.randomUUID().toString();`.
Enums `@Enumerated(STRING) @Column(length = 20)`. Timestamps are `Instant`,
defaulted to `Instant.now()`. Named `uq_`/`fk_`/`idx_` constraints declared on
the entity. Repositories are plain Spring Data `interface … extends
JpaRepository<E, String>` (no `@Repository`), derived query methods.

**Flyway.** `spring.jpa.hibernate.ddl-auto=none`, `spring.flyway.enabled=true`.
See §5 for migration naming and the same-commit rule.

**Audit (Envers, validity strategy).** Add `@Audited` to the entities we version
(`Machine`, `Recipe`, `Action`). Because `ddl-auto=none`, each `_aud` table is
created **by hand** in the same migration as its base table. `audit/AuditRevision`
is the `@RevisionEntity(CurrentUserRevisionListener.class)`;
`audit/CurrentUserRevisionListener` stamps the ambient actor read from
`CurrentUser`. Config pins `…envers.audit_table_suffix=_aud` and
`store_data_at_delete=true`.

**Exceptions.** Domain exception is a tiny `extends RuntimeException` **in the
`service` package** with a one-line Javadoc stating its HTTP mapping. Its
`*ExceptionMapper` (`@Provider @Component`) lives in **`common/`**, one per
exception, returning `Map.of("error", "<snake_code>")`. Plain 400s throw the
built-in `jakarta.ws.rs.BadRequestException`.

**Validation is manual.** Guard clauses in the service throw
`BadRequestException`/domain exceptions. **No Bean Validation** — no
`spring-boot-starter-validation`, no `@Valid`/`@NotNull`, no `ConstraintValidator`.

**Wiring.** Constructor injection only (no `@Autowired`, no field injection).
Spring's `@Transactional` on service **writes**; reads unannotated;
`spring.jpa.open-in-view=false`. Config beans live in `config/`. App properties
use the **`ca.*`** namespace via constructor `@Value("${ca.x:default}")` with
in-code defaults; secrets via optional `.env`
(`spring.config.import=optional:file:./.env[.properties]`).

**Ambient actor & identity.** A servlet filter binds
`ScopedValue<AuthContext>` (`userId`, `email`, `via`) for the request, registered
by a `FilterRegistrationBean` in `config/` (a plain bean, **not** `@Component`, so
it doesn't get the default `/*` mapping). App code reads the caller only through
the `common/CurrentUser` facade (`require()`/`optional()`/`userId()`/`via()`).
`/api` is authenticated by the app JWT (`via = UI`); `/mcp` by a per-user personal
token (`via = MCP`); scheduled jobs are unbound (`via = SYSTEM`, no user). The
same value feeds the Envers `CurrentUserRevisionListener`.

**UI.** No server-side templating engine — a static HTML + vanilla-JS shell
rendered from JSON. Hand-rendered HTML escaping goes through `common/HtmlEscaper`;
the client writes text via `textContent` only (never `innerHTML`), and the shell
is **theme-aware**.

**Tests.** JUnit 5 + AssertJ, test classes flattened to the **module-level**
package (`…recipe`, not `…recipe.service`). `*ServiceTest` = `@DataJpaTest` +
`@AutoConfigureTestDatabase(replace = NONE)` + `@Import({…services…})` slice
against H2. `*WebTest` = `@SpringBootTest(webEnvironment = RANDOM_PORT)` +
`TestRestTemplate`, with `@TestConfiguration` `@Bean @Primary` fakes for ports
(e.g. a fake `SshExecutor`). Test method names read
`method_Condition_ExpectedOutcome`.

## 7. The untouchable invariant

**Approval is UI-only**, enforced by `GateArchTest`. MCP can register and run, but
**no MCP tool approves** — approval flows through a REST endpoint the MCP layer
never calls. **Never weaken this.** The `mcp/` module holds **no business rules**
(it's a thin adapter over feature services), which is what keeps the gate
un-bypassable. See ARCH.md's "The gate — where it is enforced".

## 8. Verify before PR

`mvn -q -B clean verify` must be **green** before opening a PR.

## 9. API Modules

compute-admin is a **single deployable artifact** (MCP server + web UI); no
module's compiled artifact is consumed as a library by another service. So
finish-branch closeouts **skip any API-Diff step**.
