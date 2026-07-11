package com.iskeru.computeadmin.mcp;

import io.modelcontextprotocol.server.McpServerFeatures;

/**
 * A registered MCP resource. Each {@code *Resource} bean contributes one
 * {@link McpServerFeatures.SyncResourceSpecification} that
 * {@code config/McpServletConfig} wires into the MCP server at startup — the same
 * discovery seam as {@link McpTool}, so the transport config knows no resource by
 * name. The MCP surface exposes read-only <em>resources</em> alongside its tools:
 * the app's public SSH key (to install on targets) and a run's captured output.
 *
 * <p>Like a tool, a resource is a <strong>thin</strong> adapter over a feature
 * service ({@code KeyService}, {@code RunService}) and holds no business rules.
 *
 * <p>spec-008.
 */
public interface McpResource {

    /** The resource's descriptor plus its read handler, ready to register. */
    McpServerFeatures.SyncResourceSpecification specification();

    /**
     * Whether reading this resource needs an authenticated caller. Defaults to
     * {@code true}: the {@code config} resource wrapper (spec-008) refuses it on a
     * tokenless MCP session, so only the self-setup bootstrap tools reach an
     * unauthenticated agent — resources are never a tokenless side door. Mirrors
     * {@link McpTool#requiresAuth()}.
     */
    default boolean requiresAuth() {
        return true;
    }
}
