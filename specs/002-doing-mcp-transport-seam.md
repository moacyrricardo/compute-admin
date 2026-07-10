# 002 — MCP transport seam

> **Status:** doing — branch `moacyrricardo/spec-002-mcp-transport-seam`
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

- **MCP transport is unauthenticated** (ARCH.md S8) — no bearer token; anyone who
  reaches `/mcp` can call tools. Paired with the deferred auth work (S1) noted in
  001.
- The actor label (`MCP` vs `UI`) is **ambient and self-asserted** — it
  distinguishes transports for audit, not authenticated principals. It is not a
  security boundary on its own until auth exists; the gate's real guarantee is
  structural (no approve tool in `mcp` — spec 004/008).
- If we later adopt the single-endpoint Streamable-HTTP transport, it swaps in
  behind the same servlet seam; out of scope here.
