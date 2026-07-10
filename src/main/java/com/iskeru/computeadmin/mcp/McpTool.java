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
 * <p>spec-002.
 */
public interface McpTool {

    /** The tool's schema plus its call handler, ready to register on the server. */
    McpServerFeatures.SyncToolSpecification specification();
}
