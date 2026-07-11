package com.iskeru.computeadmin.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iskeru.computeadmin.run.model.Run;
import com.iskeru.computeadmin.run.service.RunOutputHub;
import com.iskeru.computeadmin.run.service.RunService;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Run MCP tool {@code run_action(machineId, actionId, params)} — the MCP half of
 * the single gate entry point. It delegates straight to {@link RunService#run}
 * (spec-005), which is the <strong>only</strong> place the gate is enforced:
 * the action must be {@code APPROVED}, unmutated since approval, and its params
 * must validate, else a typed exception is surfaced here as an MCP tool error. This
 * tool holds no gate logic of its own and — like every {@code mcp} class — never
 * references the approval state machine or a repository (asserted by
 * {@code GateArchTest}), so there is no MCP path to approval.
 *
 * <p>Output is streamed as <strong>MCP progress</strong>: the tool subscribes to
 * the run's {@link RunOutputHub} and forwards each stdout/stderr chunk as a
 * {@code notifications/progress} message (when the client supplied a
 * {@code progressToken}), then returns the {@code runId} and final status once the
 * run completes (or a bounded wait elapses).
 *
 * <p>spec-008.
 */
@Component
public class RunActionTool implements McpTool {

    /** Upper bound on how long the tool blocks streaming a single run's output. */
    private static final long MAX_WAIT_SECONDS = 120;

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "machineId": {"type": "string", "description": "Machine to run against."},
                "actionId": {"type": "string", "description": "Approved action to run."},
                "params": {"type": "object", "description": "Values for the action's declared params."}
              },
              "required": ["machineId", "actionId"]
            }
            """;

    private final RunService runService;
    private final ObjectMapper objectMapper;

    public RunActionTool(RunService runService, ObjectMapper objectMapper) {
        this.runService = runService;
        this.objectMapper = objectMapper;
    }

    @Override
    public McpServerFeatures.SyncToolSpecification specification() {
        McpSchema.Tool tool = new McpSchema.Tool(
                "run_action",
                "Run an APPROVED action on one of your machines; refuses anything not approved or with invalid params.",
                INPUT_SCHEMA);
        // Use the CallToolRequest handler (not the arguments-only form) so the
        // client's progressToken is available for MCP progress notifications.
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(this::call)
                .build();
    }

    private McpSchema.CallToolResult call(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        try {
            Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
            String machineId = (String) arguments.get("machineId");
            String actionId = (String) arguments.get("actionId");
            Map<String, String> params = stringParams(arguments.get("params"));

            // The gate is enforced entirely inside RunService.run; a refusal
            // (not approved / mutated / invalid params / not found) throws here.
            Run run = runService.run(machineId, actionId, params);
            String runId = run.getId();

            streamToCompletion(exchange, runId, request.progressToken());

            // Re-read for the final status/exit after the run settled.
            Run settled = runService.requireRun(runId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("runId", runId);
            result.put("status", settled.getStatus().name());
            result.put("exitCode", settled.getExitCode());
            return McpSchema.CallToolResult.builder()
                    .addTextContent(objectMapper.writeValueAsString(result))
                    .build();
        } catch (Exception e) {
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Failed to run action: " + e.getMessage())
                    .isError(true)
                    .build();
        }
    }

    /**
     * Subscribes to the run's output and blocks until it completes (bounded by
     * {@link #MAX_WAIT_SECONDS}), forwarding each chunk as an MCP progress
     * notification when the client requested progress. A late subscriber still
     * replays the buffered output and the completion signal (see {@link RunOutputHub}).
     */
    private void streamToCompletion(McpSyncServerExchange exchange, String runId, String progressToken) {
        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger progress = new AtomicInteger();
        runService.subscribeToOutput(runId, new RunOutputHub.Subscriber() {
            @Override
            public void onEvent(RunOutputHub.OutputEvent event) {
                if (progressToken != null) {
                    exchange.progressNotification(new McpSchema.ProgressNotification(
                            progressToken, progress.incrementAndGet(), null,
                            event.stream() + ": " + event.data()));
                }
            }

            @Override
            public void onComplete() {
                done.countDown();
            }
        });
        try {
            done.await(MAX_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Map<String, String> stringParams(Object raw) {
        Map<String, String> params = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                if (key != null && value != null) {
                    params.put(key.toString(), value.toString());
                }
            });
        }
        return params;
    }
}
