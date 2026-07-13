package com.iskeru.computeadmin.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iskeru.computeadmin.machine.api.MachineDtos.McpMachineView;
import com.iskeru.computeadmin.machine.service.MachineService;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Read-only MCP tool {@code list_machines(tags?)}. Delegates straight to
 * {@link MachineService#list(List)} and serializes the result — it holds no
 * business logic and depends only on the feature service, never a repository.
 * This is the reference for the {@code mcp}-is-a-thin-adapter rule.
 *
 * <p>Scopes to the caller: {@code MachineService.list} reads
 * {@code CurrentUser.require()}, which is in scope inside the tool because the
 * server runs handlers on the filter-bound request thread (spec-008 immediate
 * execution; see {@code config/McpServletConfig}). The earlier spec-002 note that
 * {@code CurrentUser} was unbound inside a tool no longer applies.
 *
 * <p>Serializes the {@link McpMachineView} (id/name/status/tags only) — never the
 * SSH coordinates, so no infra/topology leaks into the LLM context (spec-028,
 * resolving ARCH S9).
 *
 * <p>spec-002; actor scoping enabled in spec-008; MCP view in spec-028.
 */
@Component
public class ListMachinesTool implements McpTool {

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "tags": {
                  "type": "array",
                  "items": {"type": "string"},
                  "description": "Optional tag names to filter by (OR — a machine carrying any of them matches); omit for all machines."
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
                "List registered machines, optionally filtered by tags (OR).",
                INPUT_SCHEMA);
        return new McpServerFeatures.SyncToolSpecification(tool, this::call);
    }

    private McpSchema.CallToolResult call(McpSyncServerExchange exchange, Map<String, Object> arguments) {
        // Under spec-008 immediate execution this runs on the filter-bound request
        // thread, so MachineService.list can scope to the current user.
        List<String> tags = new java.util.ArrayList<>();
        if (arguments != null && arguments.get("tags") instanceof List<?> raw) {
            for (Object value : raw) {
                if (value != null) {
                    tags.add(value.toString());
                }
            }
        }
        List<McpMachineView> views = machineService.list(tags).stream()
                .map(McpMachineView::of)
                .toList();
        try {
            return McpSchema.CallToolResult.builder()
                    .addTextContent(objectMapper.writeValueAsString(views))
                    .build();
        } catch (JsonProcessingException e) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Failed to serialize machines: " + e.getMessage())
                    .isError(true)
                    .build();
        }
    }
}
