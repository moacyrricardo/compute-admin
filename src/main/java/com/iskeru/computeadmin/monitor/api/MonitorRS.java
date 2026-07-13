package com.iskeru.computeadmin.monitor.api;

import com.iskeru.computeadmin.auth.api.Secured;
import com.iskeru.computeadmin.monitor.service.MonitorService;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.springframework.stereotype.Component;

import java.util.List;

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

    /**
     * The fleet dashboard read (spec-029). Optional scoping: {@code ?tag=} (repeatable)
     * narrows to machines carrying any of the tags (OR), {@code ?machineId=} (repeatable)
     * restricts to an explicit in-scope id set — the client's visible selection, so a
     * filtered-out machine is never enumerated and thus never polled. Both absent ⇒ the
     * whole owned fleet. Owner-scoped: another user's machines are simply absent.
     */
    @GET
    public MonitorDtos.Dashboard dashboard(@QueryParam("tag") List<String> tags,
                                           @QueryParam("machineId") List<String> machineIds) {
        return MonitorDtos.Dashboard.of(monitorService.listMonitors(tags, machineIds));
    }
}
