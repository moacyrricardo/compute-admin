package com.iskeru.computeadmin.mcp.api;

import com.iskeru.computeadmin.auth.api.Secured;
import com.iskeru.computeadmin.mcp.api.McpCatalogDtos.Catalog;
import com.iskeru.computeadmin.mcp.api.McpCatalogDtos.Tool;
import com.iskeru.computeadmin.mcp.api.McpCatalogDtos.ToolGroup;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Read-only catalogue of the {@code /mcp} tool surface for the UI's MCP-surface
 * screen ({@code GET /api/mcp/tools}). {@code @Secured}: a signed-in UI caller only,
 * like the rest of {@code /api}.
 *
 * <p>The catalogue is a <strong>curated</strong> description that mirrors the tools
 * spec-008 registers, grouped by kind (Read / Create / Run / Bootstrap) with a
 * human signature and one-line each. It intentionally re-states rather than reflects
 * the registered {@code McpTool} beans: the signatures and grouping are the legible
 * trust model, not the wire schema. {@code McpCatalogWebTest} pins it in sync with
 * the actual registered tool names so it cannot silently drift.
 *
 * <p>This resource holds no business rules and reaches neither a repository nor the
 * approval service; it is safe under {@code GateArchTest}'s scan of the
 * {@code mcp} package.
 *
 * <p>spec-012.
 */
@Component
@Path("/mcp/tools")
@Produces(MediaType.APPLICATION_JSON)
@Secured
public class McpCatalogRS {

    private static final Catalog CATALOG = new Catalog(
            List.of(
                    new ToolGroup("Read", List.of(
                            new Tool("list_machines", "list_machines(tag?)",
                                    "List the machines you own, optionally filtered by tag."),
                            new Tool("list_recipes", "list_recipes(machineId)",
                                    "Recipes on one of your machines."),
                            new Tool("list_actions", "list_actions(machineId, recipeId)",
                                    "Actions on a recipe, including ones still pending_approval."),
                            new Tool("list_blueprints", "list_blueprints()",
                                    "Your machine-independent recipe blueprints."),
                            new Tool("get_run", "get_run(runId)",
                                    "Lifecycle and output of one of your runs."))),
                    new ToolGroup("Create (never approves)", List.of(
                            new Tool("register_machine", "register_machine(host, port?, loginUser)",
                                    "Register an SSH-reachable machine you own."),
                            new Tool("tag_machine", "tag_machine(machineId, tags)",
                                    "Attach tags to one of your machines."),
                            new Tool("add_recipe", "add_recipe(machineId, name, ...)",
                                    "Author a recipe on one of your machines."),
                            new Tool("add_action", "add_action(recipeId, ...)",
                                    "Add a runnable action to a recipe. It lands DRAFT and needs UI approval before it can run."),
                            new Tool("discover_recipes", "discover_recipes(machineId)",
                                    "Probe a machine and propose recipes; proposals land PENDING_APPROVAL, never approved."),
                            new Tool("add_blueprint", "add_blueprint(name, ...)",
                                    "Author a machine-independent recipe blueprint (never runnable, no approval)."),
                            new Tool("add_blueprint_action", "add_blueprint_action(blueprintId, ...)",
                                    "Add an action template to a blueprint."),
                            new Tool("instantiate_blueprint", "instantiate_blueprint(blueprintId, machineIds?/tag?)",
                                    "Instantiate a blueprint onto machines; actions land PENDING_APPROVAL, never approved."))),
                    new ToolGroup("Run", List.of(
                            new Tool("run_action", "run_action(machineId, actionId, params)",
                                    "Run an APPROVED action on one of your machines; refused otherwise or on invalid params. Streams output via MCP progress."))),
                    new ToolGroup("Bootstrap", List.of(
                            new Tool("begin_setup", "begin_setup()",
                                    "Start MCP pairing: returns a deviceCode to poll and a URL for a human to approve."),
                            new Tool("complete_setup", "complete_setup(deviceCode)",
                                    "Poll a pairing: returns its state, and the minted personal token once a human approves.")))),
            List.of("app public SSH key", "run output"),
            false);

    @GET
    public Catalog tools() {
        return CATALOG;
    }
}
