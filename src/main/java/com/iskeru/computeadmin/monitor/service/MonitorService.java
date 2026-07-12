package com.iskeru.computeadmin.monitor.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.machine.service.MachineService;
import com.iskeru.computeadmin.recipe.model.Action;
import com.iskeru.computeadmin.recipe.model.Recipe;
import com.iskeru.computeadmin.recipe.model.RecipeType;
import com.iskeru.computeadmin.recipe.service.RecipeService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Enumerates the current user's {@link RecipeType#MONITOR}-classified actions so
 * the monitor dashboard (spec-024) can render them, grouped per machine. This is a
 * pure read aggregate: it holds no business rule, mutates nothing, and enforces the
 * gate nowhere — it only <em>lists</em> what already exists, owner-scoped.
 *
 * <p>Enumeration is <strong>by classification</strong>, never by a hard-coded recipe
 * list: any {@code MONITOR}-typed recipe (the universal host monitor of spec-023, the
 * app-monitor families of spec-025) surfaces here without a code change. The
 * host-vs-app split the UI needs is <em>derived</em> per the spec-022 convention — an
 * app-level monitor is a {@code MONITOR} action carrying an {@code APP_PORT_LIST}
 * param, a host-level monitor is one without it — and is computed by the DTO, not
 * stored.
 *
 * <p>Ownership is delegated wholesale to {@link MachineService}/{@link RecipeService}
 * (service→service, never a repository): every machine, recipe, and action returned
 * is already the current user's, so a not-owned id is simply absent (never leaked).
 *
 * <p>spec-024.
 */
@Service
public class MonitorService {

    /** One machine and the {@code MONITOR} recipes (with their actions) it carries. */
    public record MachineMonitors(Machine machine, List<MonitorRecipe> recipes) {
    }

    /**
     * A {@code MONITOR}-typed recipe paired with its (already-loaded) actions and its
     * discovery-pre-filled {@code (app-name, port)} list (spec-025) — the apps every
     * probe action in the recipe fans out over. Empty for host-vitals (spec-023) and
     * any recipe whose {@code appPortList} is unset.
     */
    public record MonitorRecipe(Recipe recipe, List<Action> actions, List<AppPort> appPortList) {
    }

    /** One pre-filled app the dashboard shows/edits and the poller probes (spec-022/025). */
    public record AppPort(String appName, int port, String runtime) {
    }

    private final MachineService machineService;
    private final RecipeService recipeService;
    private final ObjectMapper json;

    public MonitorService(MachineService machineService, RecipeService recipeService, ObjectMapper json) {
        this.machineService = machineService;
        this.recipeService = recipeService;
        this.json = json;
    }

    /**
     * Every one of the current user's machines with its {@code MONITOR} recipes and
     * their actions. A machine with no monitor recipe is still returned (an empty host
     * panel) so the dashboard can show it and offer discovery.
     */
    public List<MachineMonitors> listMonitors() {
        List<MachineMonitors> out = new ArrayList<>();
        for (Machine machine : machineService.list(null)) {
            List<MonitorRecipe> recipes = new ArrayList<>();
            for (Recipe recipe : recipeService.listForMachine(machine.getId())) {
                if (recipe.getType() != RecipeType.MONITOR) {
                    continue;
                }
                recipes.add(new MonitorRecipe(recipe, recipeService.listActions(recipe.getId()),
                        parseAppPortList(recipe.getAppPortList())));
            }
            out.add(new MachineMonitors(machine, recipes));
        }
        return out;
    }

    /**
     * Parses a recipe's stored {@code appPortList} JSON ({@code [{"appName","port",
     * "runtime"}]}, spec-025) into structured items for the dashboard. Tolerant: a
     * null/blank/malformed value yields an empty list (the recipe simply has no
     * pre-filled apps yet) rather than failing the whole read.
     */
    private List<AppPort> parseAppPortList(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return List.of();
        }
        List<AppPort> items = new ArrayList<>();
        try {
            JsonNode root = json.readTree(rawJson);
            if (root.isArray()) {
                for (JsonNode node : root) {
                    JsonNode appName = node.get("appName");
                    JsonNode port = node.get("port");
                    JsonNode runtime = node.get("runtime");
                    if (appName != null && port != null) {
                        items.add(new AppPort(appName.asText(), port.asInt(),
                                runtime == null || runtime.isNull() ? null : runtime.asText()));
                    }
                }
            }
        } catch (JsonProcessingException e) {
            return List.of();
        }
        return items;
    }
}
