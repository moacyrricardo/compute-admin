package com.iskeru.computeadmin.demo;

import com.iskeru.computeadmin.auth.model.AppUser;
import com.iskeru.computeadmin.auth.repository.AppUserRepository;
import com.iskeru.computeadmin.auth.service.AuthService;
import com.iskeru.computeadmin.common.AuthContext;
import com.iskeru.computeadmin.common.CurrentUser;
import com.iskeru.computeadmin.discovery.service.DiscoveryService;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.machine.service.MachineService;
import com.iskeru.computeadmin.machine.service.MachineService.RegisterMachineInput;
import com.iskeru.computeadmin.recipe.model.Action;
import com.iskeru.computeadmin.recipe.model.ApprovalState;
import com.iskeru.computeadmin.recipe.model.Recipe;
import com.iskeru.computeadmin.recipe.service.ApprovalService;
import com.iskeru.computeadmin.recipe.service.RecipeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The {@code demo}-profile boot seeder for the fake-fleet harness (see
 * {@code demo/fake-fleet.md} / {@code demo/steps.md}). On boot it:
 *
 * <ol>
 *   <li>creates the demo user <b>{@code demo@example.com} / {@code demo-pass}</b> (via the
 *       normal {@link AuthService}, bcrypt-hashed like any user);</li>
 *   <li><b>pre-seeds {@code api-prod-2} fully</b> — registers it, runs the normal
 *       {@link DiscoveryService} (which drives the {@link CannedSshExecutor}), and
 *       <b>approves</b> every proposed monitor/host-vitals action through the real
 *       {@link ApprovalService} — so the Monitor shows two machines immediately.</li>
 * </ol>
 *
 * <p>Everything goes through the ordinary services so the gate invariant holds: discovery
 * only <em>proposes</em> ({@code PENDING_APPROVAL}); approval is the same UI-path write the
 * ActionRS {@code /approve} endpoint takes. {@code web-prod-1} is deliberately <b>not</b>
 * seeded — it is added on camera in {@code steps.md} §1.
 *
 * <p>Idempotent: if the demo user already exists (a persisted {@code ./data-demo}) it does
 * nothing, so a reboot against the same throwaway DB is safe.
 *
 * <p>{@code @Profile("demo")} — zero effect on dev/prod/test.
 */
@Component
@Profile("demo")
public class DemoSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);

    /** Documented in demo/fake-fleet.md and demo/steps.md — keep the three in sync. */
    static final String DEMO_EMAIL = "demo@example.com";
    static final String DEMO_PASSWORD = "demo-pass";

    private final AuthService authService;
    private final AppUserRepository users;
    private final MachineService machineService;
    private final DiscoveryService discoveryService;
    private final RecipeService recipeService;
    private final ApprovalService approvalService;

    public DemoSeeder(AuthService authService, AppUserRepository users,
                      MachineService machineService, DiscoveryService discoveryService,
                      RecipeService recipeService, ApprovalService approvalService) {
        this.authService = authService;
        this.users = users;
        this.machineService = machineService;
        this.discoveryService = discoveryService;
        this.recipeService = recipeService;
        this.approvalService = approvalService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.findByEmail(DEMO_EMAIL).isPresent()) {
            log.info("[demo] fleet already seeded ({}); skipping.", DEMO_EMAIL);
            return;
        }
        AppUser user = authService.register(DEMO_EMAIL, DEMO_PASSWORD, "Demo User").user();
        log.info("[demo] created demo user {} / {}", DEMO_EMAIL, DEMO_PASSWORD);

        // Bind the demo user as the acting UI caller for the ownership-scoped writes below
        // (register / discover / approve all resolve CurrentUser.require()).
        CurrentUser.runWhere(AuthContext.ui(user.getId(), user.getEmail()), () -> {
            seedApiProd2();
            return null;
        });
    }

    /** Pre-seed api-prod-2: register → discover (canned) → approve every monitor action. */
    private void seedApiProd2() {
        Machine machine = machineService.register(
                new RegisterMachineInput("api-prod-2", DemoFleet.API, 22, "api"));
        log.info("[demo] registered api-prod-2 ({})", machine.getId());

        discoveryService.discover(machine.getId());

        int approved = 0;
        for (Recipe recipe : recipeService.listForMachine(machine.getId())) {
            for (Action action : recipeService.listActions(recipe.getId())) {
                if (action.getApprovalState() == ApprovalState.PENDING_APPROVAL) {
                    approvalService.approve(action.getId());
                    approved++;
                }
            }
        }
        log.info("[demo] api-prod-2 discovered + {} actions approved", approved);
    }
}
