# 002 — MCP transport seam

> **Status:** done — branch `moacyrricardo/spec-002-mcp-transport-seam`
> (stacked on `moacyrricardo/spec-001-project-skeleton`). Linear is blocked for
> this repo, so there is no issue identifier.

## Context

The app's reason to exist is an MCP server an agent drives, kept safe by the
approval gate. Before building any domain we prove the transport seam ARCH.md
specifies — an MCP endpoint as a **raw servlet beside RESTEasy** on the same
Tomcat, wired with the MCP Java SDK, never Spring MVC, never
`spring-ai-starter-mcp-server-webmvc` — and we stand up the ambient-actor
mechanism now so every later tool and every audited write inherits it. There is
no precedent for MCP in birthday-rsvp or boletim; this spec is the reference.

## Decision

Register the MCP HTTP/SSE transport as a servlet, expose exactly one read-only
tool (`list_machines`) backed by a `MachineService` stub, and implement the
`ScopedValue<Actor>` + `CurrentActor` facade end to end.

## Implementation

**Dependency.** `io.modelcontextprotocol.sdk:mcp` (pin the version in `pom.xml`;
confirm the current release at build time).

**Actor model (foundation — placeholder, upgraded by 011).** This proves the
seam before users exist. Spec 011 replaces it with an identity-carrying
`ScopedValue<AuthContext>` + `CurrentUser` facade, renames `Actor` → `Via`, and
adds real auth (Google JWT on `/api`, per-user token on `/mcp`). Until 011, the
scope carries only the transport.
- `common/Actor` — enum `UI`, `MCP`, `SYSTEM`.
- `common/CurrentActor` — static facade over a package-private
  `ScopedValue<Actor>`: `require()` (throws if unbound), `optional()`, and the
  raw scope holder kept package-private.
- `config/ActorScopeFilter` — an `HttpFilter` that binds the scope for the
  request: `ScopedValue.where(CURRENT_ACTOR, actor).call(() -> { chain.doFilter(…);
  return null; })`. Actor is `MCP` for the MCP servlet path, `UI` for `/api/*`.
- `config/ActorScopeFilterConfig` — registers the filter via a
  `FilterRegistrationBean` at `Ordered.HIGHEST_PRECEDENCE + 10`, mapped to `/api/*`
  and the MCP path. Declared as a plain bean (**not** `@Component`) so it doesn't
  pick up the default `/*` mapping. Mirrors birthday-rsvp's
  `CurrentUserScopeFilterConfig`.

**MCP transport.**
- `config/McpServletConfig` — builds the MCP `Server`, registers
  `HttpServletSseServerTransportProvider` as a `ServletRegistrationBean` at
  `/mcp/*`, and contributes the registered `*Tool` beans to the server. The MCP
  endpoint never touches Spring MVC.
- `mcp/ListMachinesTool` — read-only tool `list_machines(tag?)`; delegates to
  `MachineService.list(tag)`. Until spec 003, `MachineService` is a stub bean in
  `machine/service` returning an empty list. Demonstrates the rule that `mcp`
  holds **no business logic** and depends only on a feature service, never a
  repository.

**Tests.**
- `ActorScopeTest` — unit test that the filter binds `MCP` vs `UI` per path and
  `CurrentActor.require()` throws when unbound.
- `McpSeamWebTest` — `@SpringBootTest(RANDOM_PORT)`; opens the MCP SSE endpoint,
  lists tools, calls `list_machines`, asserts an empty result and that the seam
  responds. A `@TestConfiguration @Bean @Primary` supplies the stub
  `MachineService`.

## Known Gaps

- **The ambient actor does NOT reach MCP tool handlers.** `ActorScopeFilter` binds
  `ScopedValue<Actor>` on the Tomcat request thread, but the MCP SDK (verified
  against `io.modelcontextprotocol.sdk:mcp` 0.11.2) adapts a
  `SyncToolSpecification` via
  `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`, so the tool
  callback runs on a Reactor `boundedElastic` pool thread — not the request
  thread. Because `ScopedValue` is thread-confined, `CurrentActor.isBound()` is
  **false** inside every MCP tool, and `CurrentActor.require()` would throw
  `IllegalStateException`. This contradicts the "every later tool and every
  audited write inherits it" claim above: on the `/api` (RESTEasy) path the actor
  is bound and inherited as designed; on the `/mcp` path it is **not**.
  `list_machines` never reads the actor, so nothing breaks today and
  `McpSeamWebTest` passes — the trap is silent. The propagation itself is left to
  the tool that first needs the actor (spec 008's write/run tools, hardened by
  spec 011's auth): those must re-establish the binding on the handler thread
  (e.g. bridge the request context into the tool callback, or bind from the
  MCP session), not assume `CurrentActor.require()` works. `McpSeamWebTest` does
  **not** prove actor propagation into tools — that remains unproven here.
- **MCP transport is unauthenticated** (ARCH.md S8) — no bearer token; anyone who
  reaches `/mcp` can call tools. Paired with the deferred auth work (S1) noted in
  001.
- The actor label (`MCP` vs `UI`) is **ambient and self-asserted** — it
  distinguishes transports for audit, not authenticated principals. It is not a
  security boundary on its own until auth exists; the gate's real guarantee is
  structural (no approve tool in `mcp` — spec 004/008).
- If we later adopt the single-endpoint Streamable-HTTP transport, it swaps in
  behind the same servlet seam; out of scope here.

## Implementation Notes

How the shipped branch differs from the spec above.

- **Headline divergence — the ambient actor does not reach MCP tool handlers.**
  The Decision claimed the `ScopedValue<Actor>` + `CurrentActor` facade is wired
  "end to end" and that "every later tool and every audited write inherits it."
  During implementation we found the MCP SDK (`io.modelcontextprotocol.sdk:mcp`
  0.11.2) adapts a `SyncToolSpecification` via
  `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`, so tool
  callbacks run on a Reactor pool thread, not the Tomcat request thread where
  `ActorScopeFilter` binds the scope. Because a `ScopedValue` is thread-confined,
  `CurrentActor` is **unbound** inside every MCP tool. The actor reaches the
  `/api` (RESTEasy) path as designed but **not** the `/mcp` tool path. The
  seam still ships (the `/api` side works; `list_machines` never reads the actor,
  so nothing breaks), but propagation into tools is deferred to whichever tool
  first needs it (spec 008/011), which must re-establish the binding on the
  handler thread. This is captured in full under "Known Gaps" (added on this
  branch as a dedicated commit) and mirrored in Javadoc on `McpServletConfig`,
  `ListMachinesTool`, and `McpSeamWebTest`; the web test explicitly does not
  assert actor propagation into tools.
- **Actor scope stays fully encapsulated in `CurrentActor`.** The spec sketched
  the filter calling `ScopedValue.where(CURRENT_ACTOR, actor).call(...)` against a
  package-private holder. Instead the raw `ScopedValue<Actor>` is package-private
  to `CurrentActor` and the filter binds through a `CurrentActor.runWhere(actor,
  op)` facade, so no code outside `CurrentActor` touches the scope holder.
- **Tool-discovery seam formalized as an `McpTool` interface.** Rather than the
  config knowing `*Tool` beans directly, each tool bean implements `McpTool` and
  exposes `specification()`; `McpServletConfig` injects `List<McpTool>` and
  contributes them all. `ListMachinesTool` is the reference implementation.
- **MCP server/transport specifics.** Used `McpSyncServer` (`McpServer.sync(...)`)
  with `SyncToolSpecification`. Transport endpoints are explicit: servlet mounted
  at `/mcp/*`, SSE at `/mcp/sse`, message at `/mcp/message` — the spec left the
  sub-paths unspecified. `ActorScopeFilterConfig` enables async support on the
  filter because the SSE endpoint calls `startAsync()`.
- **`MachineService` stub returns `List<String>`.** The spec called for an empty
  list of machines; the placeholder type is `List<String>` (not yet a `Machine`
  entity/DTO). Spec 003 replaces both the body and the return type.
- **Dependency pinning.** The MCP SDK is pinned via a `${mcp-sdk.version}`
  property in `pom.xml` (0.11.2 at build time), per the spec's instruction to pin
  the version.
- **Change division.** No `CONTRIBUTING.md` in this repo, so the change-division
  assessment is skipped. For the record, the seam landed in one implementation
  commit plus a follow-up commit that documented the ambient-actor gap once it was
  discovered.
- **API Diff.** Skipped — `CLAUDE.md` "API Modules" declares none.
