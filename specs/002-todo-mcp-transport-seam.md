# 002 — MCP transport seam

## Context

The app's reason to exist is an MCP server that an agent drives, with the
approval gate keeping it safe. Before building any domain, we prove the transport
seam ARCH.md specifies: an MCP endpoint as a **raw servlet beside RESTEasy** on
the same Tomcat, wired with the MCP Java SDK — never `spring-ai-starter-mcp-
server-webmvc`, never Spring MVC. One trivial read-only tool proves the seam end
to end.

## Decision

Register the MCP HTTP/SSE transport as a servlet and expose exactly one tool,
`list_machines`, backed by a `MachineService` stub (real registry lands in 003).
Establish the ambient-actor mechanism now so every later tool inherits it.

## Implementation

- Dependency: `io.modelcontextprotocol.sdk:mcp`.
- `config/McpServletConfig` — registers `HttpServletSseServerTransportProvider`
  as a `ServletRegistrationBean` at `/mcp/*`. The MCP `Server` is built here and
  tools are contributed by `mcp` module `*Tool` beans.
- `mcp/ListMachinesTool` — read-only; delegates to `MachineService.list(tag?)`
  (stub returning empty list until 003). Demonstrates the rule that `mcp` holds
  **no business logic** and depends only on a feature service.
- **Ambient actor:** `config` filter binds a `ScopedValue<Actor>`; a
  `common/CurrentActor` facade reads it. Requests arriving on the MCP servlet
  resolve to actor `MCP`; requests on `/api` resolve to `UI`. The Envers revision
  listener (added in 004) and audit read through `CurrentActor`.

## Known Gaps

- **MCP transport is unauthenticated** (ARCH.md S8). No bearer token; anyone who
  can reach `/mcp` can call tools. Paired with the deferred auth work flagged in
  001 (S1).
- The actor label (`MCP` vs `UI`) is **ambient and self-asserted**, not
  authenticated — it distinguishes transports for audit, not principals. It is
  not a security boundary on its own until auth exists.
- If we later adopt the single-endpoint Streamable-HTTP transport, it swaps in
  behind the same servlet seam; out of scope here.
