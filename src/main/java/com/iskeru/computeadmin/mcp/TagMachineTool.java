package com.iskeru.computeadmin.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.machine.model.Tag;
import com.iskeru.computeadmin.machine.service.MachineService;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Create-only MCP tool {@code tag_machine(machineId, tags[])}. Delegates to
 * {@link MachineService#tag}, get-or-creating each tag for the caller. Owner-scoped
 * (a not-owned or absent machine is a 404); touches no repository and cannot
 * approve.
 *
 * <p>spec-008.
 */
@Component
public class TagMachineTool implements McpTool {

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "machineId": {"type": "string", "description": "Machine to tag."},
                "tags": {"type": "array", "items": {"type": "string"},
                    "description": "Tag names to add (get-or-created)."}
              },
              "required": ["machineId", "tags"]
            }
            """;

    private final MachineService machineService;
    private final ObjectMapper objectMapper;

    public TagMachineTool(MachineService machineService, ObjectMapper objectMapper) {
        this.machineService = machineService;
        this.objectMapper = objectMapper;
    }

    @Override
    public McpServerFeatures.SyncToolSpecification specification() {
        McpSchema.Tool tool = new McpSchema.Tool(
                "tag_machine",
                "Add tags to one of your machines.",
                INPUT_SCHEMA);
        return new McpServerFeatures.SyncToolSpecification(tool, this::call);
    }

    @SuppressWarnings("unchecked")
    private McpSchema.CallToolResult call(McpSyncServerExchange exchange, Map<String, Object> arguments) {
        try {
            Map<String, Object> args = arguments == null ? Map.of() : arguments;
            String machineId = (String) args.get("machineId");
            Set<String> names = new LinkedHashSet<>();
            if (args.get("tags") instanceof List<?> raw) {
                for (Object value : raw) {
                    if (value != null) {
                        names.add(value.toString());
                    }
                }
            }
            Machine machine = machineService.tag(machineId, names);
            return McpSchema.CallToolResult.builder()
                    .addTextContent(objectMapper.writeValueAsString(Map.of(
                            "id", machine.getId(),
                            "tags", machine.getTags().stream().map(Tag::getName).sorted().toList())))
                    .build();
        } catch (Exception e) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Failed to tag machine: " + e.getMessage())
                    .isError(true)
                    .build();
        }
    }
}
