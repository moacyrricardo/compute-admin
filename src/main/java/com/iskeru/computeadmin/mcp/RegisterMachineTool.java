package com.iskeru.computeadmin.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.machine.service.MachineService;
import com.iskeru.computeadmin.machine.service.MachineService.RegisterMachineInput;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Create-only MCP tool {@code register_machine(host, port?, loginUser)}. Delegates
 * to {@link MachineService#register} — registration is open over MCP (the core
 * invariant), and a machine carries no approval state, so there is nothing here to
 * approve. Owned by the current caller; touches no repository.
 *
 * <p>spec-008.
 */
@Component
public class RegisterMachineTool implements McpTool {

    private static final int DEFAULT_SSH_PORT = 22;

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "host": {"type": "string", "description": "Hostname or IP of the target machine."},
                "port": {"type": "integer", "description": "SSH port (default 22)."},
                "loginUser": {"type": "string", "description": "SSH login user on the target."}
              },
              "required": ["host", "loginUser"]
            }
            """;

    private final MachineService machineService;
    private final ObjectMapper objectMapper;

    public RegisterMachineTool(MachineService machineService, ObjectMapper objectMapper) {
        this.machineService = machineService;
        this.objectMapper = objectMapper;
    }

    @Override
    public McpServerFeatures.SyncToolSpecification specification() {
        McpSchema.Tool tool = new McpSchema.Tool(
                "register_machine",
                "Register an SSH-reachable machine you own.",
                INPUT_SCHEMA);
        return new McpServerFeatures.SyncToolSpecification(tool, this::call);
    }

    private McpSchema.CallToolResult call(McpSyncServerExchange exchange, Map<String, Object> arguments) {
        try {
            Map<String, Object> args = arguments == null ? Map.of() : arguments;
            String host = (String) args.get("host");
            String loginUser = (String) args.get("loginUser");
            int port = args.get("port") instanceof Number n ? n.intValue() : DEFAULT_SSH_PORT;
            Machine machine = machineService.register(new RegisterMachineInput(host, port, loginUser));
            return McpSchema.CallToolResult.builder()
                    .addTextContent(objectMapper.writeValueAsString(Map.of(
                            "id", machine.getId(),
                            "host", machine.getHost(),
                            "port", machine.getPort(),
                            "loginUser", machine.getLoginUser(),
                            "status", machine.getStatus().name())))
                    .build();
        } catch (Exception e) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Failed to register machine: " + e.getMessage())
                    .isError(true)
                    .build();
        }
    }
}
