package com.iskeru.computeadmin.mcp;

import io.modelcontextprotocol.server.McpServerFeatures;

/**
 * A registered MCP tool. Each {@code *Tool} bean contributes one
 * {@link McpServerFeatures.SyncToolSpecification} that
 * {@code config/McpServletConfig} wires into the MCP server at startup — the seam
 * by which tools are discovered without the transport config knowing any of them
 * by name.
 *
 * <p>The {@code mcp} module is a <strong>thin</strong> adapter: an
 * implementation maps a tool onto a feature service and holds no business rules,
 * so the approval gate can't be bypassed by talking to MCP.
 *
 * <p>spec-002; {@link #requiresAuth()} added in spec-008.
 */
public interface McpTool {

    /** The tool's schema plus its call handler, ready to register on the server. */
    McpServerFeatures.SyncToolSpecification specification();

    /**
     * Whether this tool needs an authenticated caller. Almost every tool does — the
     * {@code config} tool wrapper (spec-008) refuses it on a tokenless MCP session.
     * Only the self-setup bootstrap tools ({@code begin_setup}/{@code complete_setup})
     * override this to {@code false}: they expose no user data and are the sole
     * capability a not-yet-paired agent may reach (resolves S8). See
     * {@code McpTokenAuthFilter} and {@code McpServletConfig}.
     */
    default boolean requiresAuth() {
        return true;
    }
}
