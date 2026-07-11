package com.iskeru.computeadmin.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iskeru.computeadmin.auth.service.PairingService;
import com.iskeru.computeadmin.auth.service.PairingService.BeginResult;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Bootstrap MCP tool {@code begin_setup()} — one of the only two capabilities a
 * <strong>tokenless</strong> MCP session may reach (spec-008; see
 * {@code McpTokenAuthFilter}/{@code McpServletConfig}). It delegates to
 * {@link PairingService#begin} to start an RFC 8628-style device-authorization
 * pairing and returns the {@code deviceCode} the agent then polls with
 * {@code complete_setup}, plus the {@code verificationUrl} a human opens to approve
 * it (Google sign-in) in the UI. It exposes no user data and cannot approve.
 *
 * <p>{@link #requiresAuth()} is {@code false}: this is the pre-authentication
 * self-setup handshake.
 *
 * <p>spec-008 (device pairing from spec-011).
 */
@Component
public class BeginSetupTool implements McpTool {

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {},
              "required": []
            }
            """;

    private final PairingService pairingService;
    private final ObjectMapper objectMapper;

    public BeginSetupTool(PairingService pairingService, ObjectMapper objectMapper) {
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
                "begin_setup",
                "Start MCP pairing: returns a deviceCode to poll and a URL for a human to approve.",
                INPUT_SCHEMA);
        return new McpServerFeatures.SyncToolSpecification(tool, this::call);
    }

    private McpSchema.CallToolResult call(McpSyncServerExchange exchange, Map<String, Object> arguments) {
        try {
            BeginResult result = pairingService.begin();
            return McpSchema.CallToolResult.builder()
                    .addTextContent(objectMapper.writeValueAsString(Map.of(
                            "deviceCode", result.deviceCode(),
                            "userCode", result.userCode(),
                            "verificationUrl", result.verificationUrl(),
                            "expiresIn", result.expiresIn(),
                            "interval", result.interval())))
                    .build();
        } catch (Exception e) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Failed to begin setup: " + e.getMessage())
                    .isError(true)
                    .build();
        }
    }
}
