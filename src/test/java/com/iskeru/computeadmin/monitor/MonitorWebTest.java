package com.iskeru.computeadmin.monitor;

import com.iskeru.computeadmin.auth.api.AuthDtos;
import com.iskeru.computeadmin.machine.api.MachineDtos;
import com.iskeru.computeadmin.monitor.api.MonitorDtos;
import com.iskeru.computeadmin.recipe.api.RecipeDtos;
import com.iskeru.computeadmin.recipe.model.ParamKind;
import com.iskeru.computeadmin.recipe.model.RecipeType;
import com.iskeru.computeadmin.recipe.model.TokenKind;
import com.iskeru.computeadmin.recipe.service.ActionService.ArgTokenInput;
import com.iskeru.computeadmin.recipe.service.ActionService.ParamDefInput;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The monitor-dashboard enumeration over the real HTTP surface (spec-024): a
 * UI-authenticated user gets exactly his own {@code MONITOR}-classified actions,
 * split into host-panel actions and per-app probes; a non-{@code MONITOR} action is
 * never enumerated, and another user's monitors are invisible (owner-scoped, a
 * not-owned machine is simply absent).
 *
 * <p>spec-024.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MonitorWebTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void dashboard_EnumeratesOnlyMonitorActions_SplitHostVsApp() {
        AuthDtos.Session owner = login("monitor-owner@example.com");
        String machineId = registerMachine(owner);

        // A MONITOR recipe with a host action (no APP_PORT_LIST) and an app probe (with it).
        String monitorRecipe = createRecipe(owner, machineId, "monitor machine", RecipeType.MONITOR);
        String hostActionId = addHostAction(owner, monitorRecipe);
        String appActionId = addAppAction(owner, monitorRecipe);

        // A non-MONITOR recipe + action that must never surface on the dashboard.
        String nginxRecipe = createRecipe(owner, machineId, "nginx", RecipeType.NGINX);
        String nginxActionId = addHostAction(owner, nginxRecipe);

        MonitorDtos.Dashboard dashboard = getDashboard(owner);
        MonitorDtos.MonitorMachineView machine = machineFor(dashboard, machineId);

        assertThat(ids(machine.hostActions())).containsExactly(hostActionId);
        assertThat(ids(machine.appActions())).containsExactly(appActionId);

        // The host action is classified host-side; the app probe app-side.
        assertThat(machine.hostActions().get(0).hasAppParam()).isFalse();
        assertThat(machine.hostActions().get(0).recipeType()).isEqualTo(RecipeType.MONITOR);
        assertThat(machine.appActions().get(0).hasAppParam()).isTrue();

        // The non-MONITOR action appears nowhere.
        assertThat(ids(machine.hostActions())).doesNotContain(nginxActionId);
        assertThat(ids(machine.appActions())).doesNotContain(nginxActionId);

        // The fleet per-app rollup (spec-029) is always present; with no discovery
        // pre-fill (appPortList set only by discovery) this machine has no app cards yet.
        assertThat(machine.apps()).isEmpty();
    }

    @Test
    void dashboard_ScopedToMachineIdQuery_EnumeratesOnlyThoseMachines() {
        AuthDtos.Session owner = login("fleet-scope@example.com");
        String one = registerMachineHost(owner, "fleet-one");
        String two = registerMachineHost(owner, "fleet-two");

        // No scope ⇒ the whole owned fleet.
        MonitorDtos.Dashboard all = getDashboard(owner);
        assertThat(all.machines()).extracting(MonitorDtos.MonitorMachineView::machineId)
                .contains(one, two);

        // ?machineId= restricts to the client's visible set — the rest is never
        // enumerated (so never polled). spec-029.
        MonitorDtos.Dashboard scoped = rest.exchange(
                "/api/monitor?machineId=" + one, HttpMethod.GET,
                new HttpEntity<>(bearer(owner.token())), MonitorDtos.Dashboard.class).getBody();
        assertThat(scoped.machines()).extracting(MonitorDtos.MonitorMachineView::machineId)
                .containsExactly(one);
    }

    @Test
    void dashboard_OfAnotherUsersMonitors_IsNotVisible() {
        AuthDtos.Session a = login("monitor-a@example.com");
        AuthDtos.Session b = login("monitor-b@example.com");
        String aMachine = registerMachine(a);
        String aRecipe = createRecipe(a, aMachine, "monitor machine", RecipeType.MONITOR);
        String aAction = addHostAction(a, aRecipe);

        // b sees none of a's machines/actions.
        MonitorDtos.Dashboard bDashboard = getDashboard(b);
        assertThat(bDashboard.machines())
                .noneMatch(m -> m.machineId().equals(aMachine));
        assertThat(bDashboard.machines().stream()
                .flatMap(m -> java.util.stream.Stream.concat(
                        m.hostActions().stream(), m.appActions().stream()))
                .map(MonitorDtos.MonitorActionView::id))
                .doesNotContain(aAction);
    }

    @Test
    void dashboard_SurfacesApprovedAppOps_CorrelatedByAppNameParam() {
        AuthDtos.Session owner = login("appops-owner@example.com");
        String machineId = registerMachine(owner);
        String recipe = createRecipe(owner, machineId, "systemd", RecipeType.SYSTEMD);

        // An APPROVED ops action targeting two apps, and a not-yet-approved one.
        String approvedOp = addOpsAction(owner, recipe, "restart", List.of("orders", "billing"));
        approveAction(owner, approvedOp);
        addOpsAction(owner, recipe, "tail-logs", List.of("orders"));

        MonitorDtos.Dashboard dashboard = getDashboard(owner);
        MonitorDtos.MonitorMachineView machine = machineFor(dashboard, machineId);

        // Only the approved op surfaces (the facade never shows a runnable it would refuse),
        // carrying its target apps and the reserved param name.
        assertThat(machine.appOps()).extracting(MonitorDtos.AppOpView::id).containsExactly(approvedOp);
        MonitorDtos.AppOpView op = machine.appOps().get(0);
        assertThat(op.targetApps()).containsExactlyInAnyOrder("orders", "billing");
        assertThat(op.appParamName()).isEqualTo("app-name");
        assertThat(op.recipeType()).isEqualTo(RecipeType.SYSTEMD);

        // The resolver keys the op to exactly the apps its `app-name` param can target.
        assertThat(MonitorDtos.opsForApp(machine, "orders"))
                .extracting(MonitorDtos.AppOpView::id).containsExactly(approvedOp);
        assertThat(MonitorDtos.opsForApp(machine, "billing"))
                .extracting(MonitorDtos.AppOpView::id).containsExactly(approvedOp);
        assertThat(MonitorDtos.opsForApp(machine, "web")).isEmpty();
    }

    // --- setup helpers ------------------------------------------------------

    /** An ops action keyed by the reserved `app-name` ALLOWED_SET (spec-026). */
    private String addOpsAction(AuthDtos.Session s, String recipeId, String name, List<String> apps) {
        RecipeDtos.AddActionRequest body = new RecipeDtos.AddActionRequest(
                recipeId, name, name + " op", true,
                List.of(new ArgTokenInput(TokenKind.LITERAL, "systemctl"),
                        new ArgTokenInput(TokenKind.LITERAL, "restart"),
                        new ArgTokenInput(TokenKind.PARAM, "app-name")),
                List.of(new ParamDefInput("app-name", ParamKind.ALLOWED_SET, null, null, null, apps)));
        return post("/api/actions", body, s.token(), RecipeDtos.ActionView.class).id();
    }

    private void approveAction(AuthDtos.Session s, String actionId) {
        postNoBody("/api/actions/" + actionId + "/submit", s.token());
        postNoBody("/api/actions/" + actionId + "/approve", s.token());
    }

    private void postNoBody(String path, String token) {
        rest.exchange(path, HttpMethod.POST, new HttpEntity<>(bearer(token)), String.class);
    }


    private MonitorDtos.Dashboard getDashboard(AuthDtos.Session s) {
        return rest.exchange("/api/monitor", HttpMethod.GET, new HttpEntity<>(bearer(s.token())),
                MonitorDtos.Dashboard.class).getBody();
    }

    private MonitorDtos.MonitorMachineView machineFor(MonitorDtos.Dashboard dashboard, String machineId) {
        return dashboard.machines().stream()
                .filter(m -> m.machineId().equals(machineId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("machine " + machineId + " not on the dashboard"));
    }

    private static List<String> ids(List<MonitorDtos.MonitorActionView> actions) {
        return actions.stream().map(MonitorDtos.MonitorActionView::id).toList();
    }

    private String registerMachine(AuthDtos.Session s) {
        return registerMachineHost(s, "monitor-host");
    }

    private String registerMachineHost(AuthDtos.Session s, String host) {
        return post("/api/machines",
                new MachineDtos.RegisterMachineRequest(host, host, 22, "root"),
                s.token(), MachineDtos.MachineView.class).id();
    }

    private String createRecipe(AuthDtos.Session s, String machineId, String name, RecipeType type) {
        return post("/api/recipes",
                new RecipeDtos.RecipeRequest(machineId, name, name + " recipe", type),
                s.token(), RecipeDtos.RecipeView.class).id();
    }

    /** A param-free host action (top -bn1 style). */
    private String addHostAction(AuthDtos.Session s, String recipeId) {
        RecipeDtos.AddActionRequest body = new RecipeDtos.AddActionRequest(
                recipeId, "cpu", "CPU snapshot", false,
                List.of(new ArgTokenInput(TokenKind.LITERAL, "top"),
                        new ArgTokenInput(TokenKind.LITERAL, "-bn1")),
                List.of());
        return post("/api/actions", body, s.token(), RecipeDtos.ActionView.class).id();
    }

    /** An app probe fanning out over a single APP_PORT_LIST composite (spec-022). */
    private String addAppAction(AuthDtos.Session s, String recipeId) {
        RecipeDtos.AddActionRequest body = new RecipeDtos.AddActionRequest(
                recipeId, "springboot health", "per-app health probe", false,
                List.of(new ArgTokenInput(TokenKind.LITERAL, "curl"),
                        new ArgTokenInput(TokenKind.PARAM, "app-name"),
                        new ArgTokenInput(TokenKind.PARAM, "port")),
                List.of(new ParamDefInput("apps", ParamKind.APP_PORT_LIST, null, null, null, null)));
        return post("/api/actions", body, s.token(), RecipeDtos.ActionView.class).id();
    }

    // --- http helpers -------------------------------------------------------

    private <T> T post(String path, Object body, String token, Class<T> type) {
        return rest.postForObject(path, new HttpEntity<>(body, bearer(token)), type);
    }

    private AuthDtos.Session login(String email) {
        org.springframework.http.ResponseEntity<AuthDtos.Session> reg = rest.postForEntity(
                "/api/auth/register",
                new AuthDtos.RegisterRequest(email, "password-123", null), AuthDtos.Session.class);
        if (reg.getStatusCode().is2xxSuccessful() && reg.getBody() != null && reg.getBody().token() != null) {
            return reg.getBody();
        }
        return rest.postForObject("/api/auth/login",
                new AuthDtos.LoginRequest(email, "password-123"), AuthDtos.Session.class);
    }

    private static HttpHeaders bearer(String jwt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);
        return headers;
    }
}
