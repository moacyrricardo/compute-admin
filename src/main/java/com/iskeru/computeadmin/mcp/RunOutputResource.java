package com.iskeru.computeadmin.mcp;

import com.iskeru.computeadmin.run.model.Run;
import com.iskeru.computeadmin.run.service.RunService;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.util.DeafaultMcpUriTemplateManagerFactory;
import io.modelcontextprotocol.util.McpUriTemplateManagerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Templated MCP resource {@code run://{runId}/output} exposing a run's captured
 * output — the recorded, resource-shaped counterpart to the {@code get_run} tool and
 * {@code run_action}'s live progress stream. Delegates to
 * {@link RunService#requireRun(String)} (owner-scoped: a not-owned or absent id is a
 * 404 surfaced as a read error), then returns the run's stdout as {@code text/plain}.
 * Touches no repository.
 *
 * <p>The MCP server routes a {@code resources/read} for a concrete URI by matching it
 * against each registered resource's URI treated as a template, so this one
 * specification serves every {@code runId}; the id is extracted from the request URI
 * with the SDK's own {@link McpUriTemplateManagerFactory}.
 *
 * <p>spec-008.
 */
@Component
public class RunOutputResource implements McpResource {

    static final String URI_TEMPLATE = "run://{runId}/output";

    private static final McpUriTemplateManagerFactory URI_TEMPLATES =
            new DeafaultMcpUriTemplateManagerFactory();

    private final RunService runService;

    public RunOutputResource(RunService runService) {
        this.runService = runService;
    }

    @Override
    public McpServerFeatures.SyncResourceSpecification specification() {
        McpSchema.Resource resource = McpSchema.Resource.builder()
                .uri(URI_TEMPLATE)
                .name("run-output")
                .title("Run output")
                .description("A run's captured stdout, by runId. The recorded counterpart to "
                        + "run_action's live output stream.")
                .mimeType("text/plain")
                .build();
        return new McpServerFeatures.SyncResourceSpecification(resource, this::read);
    }

    private McpSchema.ReadResourceResult read(McpSyncServerExchange exchange,
                                              McpSchema.ReadResourceRequest request) {
        String runId = URI_TEMPLATES.create(URI_TEMPLATE)
                .extractVariableValues(request.uri())
                .get("runId");
        Run run = runService.requireRun(runId);
        McpSchema.TextResourceContents contents =
                new McpSchema.TextResourceContents(request.uri(), "text/plain", run.getStdout());
        return new McpSchema.ReadResourceResult(List.of(contents));
    }
}
