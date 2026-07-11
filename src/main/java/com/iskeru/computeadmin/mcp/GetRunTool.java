package com.iskeru.computeadmin.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iskeru.computeadmin.run.model.Run;
import com.iskeru.computeadmin.run.service.RunService;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-only MCP tool {@code get_run(runId)}. Delegates to
 * {@link RunService#requireRun(String)} (owner-scoped, a not-owned or absent id is
 * a 404 surfaced as a tool error) and returns the run's lifecycle plus its captured
 * stdout/stderr and exit code — the recorded counterpart to {@code run_action}'s
 * live progress stream. Touches no repository.
 *
 * <p>spec-008.
 */
@Component
public class GetRunTool implements McpTool {

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "runId": {"type": "string", "description": "The run to fetch."}
              },
              "required": ["runId"]
            }
            """;

    private final RunService runService;
    private final ObjectMapper objectMapper;

    public GetRunTool(RunService runService, ObjectMapper objectMapper) {
        this.runService = runService;
        this.objectMapper = objectMapper;
    }

    @Override
    public McpServerFeatures.SyncToolSpecification specification() {
        McpSchema.Tool tool = new McpSchema.Tool(
                "get_run",
                "Fetch a run's status, exit code and captured output.",
                INPUT_SCHEMA);
        return new McpServerFeatures.SyncToolSpecification(tool, this::call);
    }

    private McpSchema.CallToolResult call(McpSyncServerExchange exchange, Map<String, Object> arguments) {
        try {
            String runId = arguments == null ? null : (String) arguments.get("runId");
            Run run = runService.requireRun(runId);
            return McpSchema.CallToolResult.builder()
                    .addTextContent(objectMapper.writeValueAsString(summarize(run)))
                    .build();
        } catch (Exception e) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Failed to get run: " + e.getMessage())
                    .isError(true)
                    .build();
        }
    }

    private static Map<String, Object> summarize(Run run) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", run.getId());
        view.put("machineId", run.getMachine().getId());
        view.put("actionId", run.getAction().getId());
        view.put("status", run.getStatus().name());
        view.put("exitCode", run.getExitCode());
        view.put("via", run.getVia().name());
        view.put("stdout", run.getStdout());
        view.put("stderr", run.getStderr());
        view.put("createdAt", run.getCreatedAt());
        view.put("startedAt", run.getStartedAt());
        view.put("finishedAt", run.getFinishedAt());
        return view;
    }
}
