package com.iskeru.computeadmin.monitor.api;

import com.iskeru.computeadmin.auth.api.Secured;
import com.iskeru.computeadmin.monitor.service.MonitorService;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.springframework.stereotype.Component;

/**
 * Read-only enumeration of the current user's {@code MONITOR}-classified actions for
 * the monitor dashboard ({@code GET /api/monitor}). {@code @Secured}: a signed-in UI
 * caller only, like the rest of {@code /api}.
 *
 * <p>This resource never runs anything and never touches the gate — the dashboard
 * drives runs through the ordinary {@code POST /api/runs} path, which enforces
 * approval + live-hash + param validation server-side. Here the client only
 * <em>discovers</em> which monitor actions exist and how to group them (host panel vs
 * per-app cards); a not-owned machine/action is simply absent (owner-scoped by the
 * services this delegates to).
 *
 * <p>spec-024.
 */
@Component
@Path("/monitor")
@Produces(MediaType.APPLICATION_JSON)
@Secured
public class MonitorRS {

    private final MonitorService monitorService;

    public MonitorRS(MonitorService monitorService) {
        this.monitorService = monitorService;
    }

    @GET
    public MonitorDtos.Dashboard dashboard() {
        return MonitorDtos.Dashboard.of(monitorService.listMonitors());
    }
}
