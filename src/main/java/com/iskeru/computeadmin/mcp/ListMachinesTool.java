package com.iskeru.computeadmin.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iskeru.computeadmin.machine.service.MachineService;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Read-only MCP tool {@code list_machines(tag?)}. Delegates straight to
 * {@link MachineService#list(String)} and serializes the result — it holds no
 * business logic and depends only on the feature service, never a repository.
 * This is the reference for the {@code mcp}-is-a-thin-adapter rule.
 *
 * <p>spec-002.
 */
@Component
public class ListMachinesTool implements McpTool {

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "tag": {
                  "type": "string",
                  "description": "Optional tag to filter machines by; omit for all machines."
                }
              },
              "required": []
            }
            """;

    private final MachineService machineService;
    private final ObjectMapper objectMapper;

    public ListMachinesTool(MachineService machineService, ObjectMapper objectMapper) {
        this.machineService = machineService;
        this.objectMapper = objectMapper;
    }

    @Override
    public McpServerFeatures.SyncToolSpecification specification() {
        McpSchema.Tool tool = new McpSchema.Tool(
                "list_machines",
                "List registered machines, optionally filtered by tag.",
                INPUT_SCHEMA);
        return new McpServerFeatures.SyncToolSpecification(tool, this::call);
    }

    private McpSchema.CallToolResult call(McpSyncServerExchange exchange, Map<String, Object> arguments) {
        // NOTE: this callback runs on a Reactor boundedElastic thread (the MCP SDK
        // adapts sync tools via Mono.fromCallable(...).subscribeOn(...)), not the
        // Tomcat request thread where McpTokenAuthFilter (spec-011) bound the
        // ScopedValue<AuthContext>. CurrentUser is therefore UNBOUND here — do not
        // call CurrentUser.require() from a tool without first re-establishing the
        // binding on this thread. See specs/002-done-mcp-transport-seam.md, "Known Gaps".
        String tag = arguments == null ? null : (String) arguments.get("tag");
        List<String> machines = machineService.list(tag);
        try {
            return McpSchema.CallToolResult.builder()
                    .addTextContent(objectMapper.writeValueAsString(machines))
                    .build();
        } catch (JsonProcessingException e) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Failed to serialize machines: " + e.getMessage())
                    .isError(true)
                    .build();
        }
    }
}
