package com.iskeru.computeadmin.monitor.service;

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

    /** A {@code MONITOR}-typed recipe paired with its (already-loaded) actions. */
    public record MonitorRecipe(Recipe recipe, List<Action> actions) {
    }

    private final MachineService machineService;
    private final RecipeService recipeService;

    public MonitorService(MachineService machineService, RecipeService recipeService) {
        this.machineService = machineService;
        this.recipeService = recipeService;
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
                recipes.add(new MonitorRecipe(recipe, recipeService.listActions(recipe.getId())));
            }
            out.add(new MachineMonitors(machine, recipes));
        }
        return out;
    }
}
