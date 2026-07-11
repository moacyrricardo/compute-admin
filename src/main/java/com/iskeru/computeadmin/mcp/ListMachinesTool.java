package com.iskeru.computeadmin.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iskeru.computeadmin.machine.model.Machine;
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
 * <p>Scopes to the caller: {@code MachineService.list} reads
 * {@code CurrentUser.require()}, which is in scope inside the tool because the
 * server runs handlers on the filter-bound request thread (spec-008 immediate
 * execution; see {@code config/McpServletConfig}). The earlier spec-002 note that
 * {@code CurrentUser} was unbound inside a tool no longer applies.
 *
 * <p>spec-002; actor scoping enabled in spec-008.
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
        // Under spec-008 immediate execution this runs on the filter-bound request
        // thread, so MachineService.list can scope to the current user.
        String tag = arguments == null ? null : (String) arguments.get("tag");
        List<Map<String, Object>> summaries = machineService.list(tag).stream()
                .map(ListMachinesTool::summarize)
                .toList();
        try {
            return McpSchema.CallToolResult.builder()
                    .addTextContent(objectMapper.writeValueAsString(summaries))
                    .build();
        } catch (JsonProcessingException e) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Failed to serialize machines: " + e.getMessage())
                    .isError(true)
                    .build();
        }
    }

    private static Map<String, Object> summarize(Machine machine) {
        return Map.of(
                "id", machine.getId(),
                "host", machine.getHost(),
                "port", machine.getPort(),
                "loginUser", machine.getLoginUser(),
                "status", machine.getStatus().name());
    }
}
