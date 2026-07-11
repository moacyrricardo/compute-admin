package com.iskeru.computeadmin.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iskeru.computeadmin.auth.service.PairingService;
import com.iskeru.computeadmin.auth.service.PairingService.PollResult;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bootstrap MCP tool {@code complete_setup(deviceCode)} — the second and last
 * capability a <strong>tokenless</strong> MCP session may reach (spec-008). It
 * polls {@link PairingService#poll}: while a human has not yet approved it returns
 * {@code PENDING}/{@code SLOW_DOWN}, and once approved in the UI it returns the
 * minted personal {@code token} exactly once (the client then reconnects with it as
 * a bearer credential). It exposes no other user data and cannot approve.
 *
 * <p>{@link #requiresAuth()} is {@code false}: it is the pre-authentication side of
 * the self-setup handshake.
 *
 * <p>spec-008 (device pairing from spec-011).
 */
@Component
public class CompleteSetupTool implements McpTool {

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "deviceCode": {"type": "string", "description": "The deviceCode returned by begin_setup."}
              },
              "required": ["deviceCode"]
            }
            """;

    private final PairingService pairingService;
    private final ObjectMapper objectMapper;

    public CompleteSetupTool(PairingService pairingService, ObjectMapper objectMapper) {
        this.pairingService = pairingService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean requiresAuth() {
        return false;
    }

    @Override
    public McpServerFeatures.SyncToolSpecification specification() {
        McpSchema.Tool tool = new McpSchema.Tool(
                "complete_setup",
                "Poll a pairing: returns the pairing state, and the minted personal token once a human approves.",
                INPUT_SCHEMA);
        return new McpServerFeatures.SyncToolSpecification(tool, this::call);
    }

    private McpSchema.CallToolResult call(McpSyncServerExchange exchange, Map<String, Object> arguments) {
        try {
            String deviceCode = arguments == null ? null : (String) arguments.get("deviceCode");
            PollResult result = pairingService.poll(deviceCode);
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("state", result.state().name());
            // The plaintext token is present only for the single APPROVED poll.
            if (result.token() != null) {
                view.put("token", result.token());
            }
            return McpSchema.CallToolResult.builder()
                    .addTextContent(objectMapper.writeValueAsString(view))
                    .build();
        } catch (Exception e) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Failed to complete setup: " + e.getMessage())
                    .isError(true)
                    .build();
        }
    }
}
