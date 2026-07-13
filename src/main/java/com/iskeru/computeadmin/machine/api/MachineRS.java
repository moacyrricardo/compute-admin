package com.iskeru.computeadmin.machine.api;

import com.iskeru.computeadmin.auth.api.Secured;
import com.iskeru.computeadmin.machine.service.ConnectionTestService;
import com.iskeru.computeadmin.machine.service.MachineService;
import com.iskeru.computeadmin.machine.service.MachineService.RegisterMachineInput;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Per-user machine registry over REST. Every operation scopes to the current user
 * (a not-owned id is 404). Registration and tagging are open here and on MCP; the
 * approval gate lives elsewhere.
 *
 * <p>spec-003.
 */
@Component
@Path("/machines")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Secured
public class MachineRS {

    /** Default SSH port applied when a register request omits one. */
    private static final int DEFAULT_PORT = 22;

    private final MachineService machineService;
    private final ConnectionTestService connectionTestService;

    public MachineRS(MachineService machineService, ConnectionTestService connectionTestService) {
        this.machineService = machineService;
        this.connectionTestService = connectionTestService;
    }

    @POST
    public MachineDtos.MachineView register(MachineDtos.RegisterMachineRequest body) {
        if (body == null) {
            throw new BadRequestException("body is required");
        }
        int port = body.port() == null ? DEFAULT_PORT : body.port();
        RegisterMachineInput input = new RegisterMachineInput(body.name(), body.host(), port, body.loginUser());
        return MachineDtos.MachineView.of(machineService.register(input));
    }

    /**
     * The caller's machines, optionally narrowed by a repeatable {@code ?tag=<name>}
     * filter (OR semantics — a machine carrying any of the given tags matches). Owner
     * scoping is unchanged: a user only ever sees their own machines (spec-018).
     */
    @GET
    public java.util.List<MachineDtos.MachineView> list(@QueryParam("tag") java.util.List<String> tags) {
        return machineService.list(tags).stream().map(MachineDtos.MachineView::of).toList();
    }

    @GET
    @Path("/{id}")
    public MachineDtos.MachineView get(@PathParam("id") String id) {
        return MachineDtos.MachineView.of(machineService.requireMachine(id));
    }

    @POST
    @Path("/{id}/tags")
    public MachineDtos.MachineView tag(@PathParam("id") String id, MachineDtos.TagRequest body) {
        Set<String> names = body == null ? Set.of() : body.names();
        if (names == null || names.isEmpty()) {
            throw new BadRequestException("names is required");
        }
        return MachineDtos.MachineView.of(machineService.tag(id, names));
    }

    @DELETE
    @Path("/{id}/tags/{name}")
    public MachineDtos.MachineView untag(@PathParam("id") String id, @PathParam("name") String name) {
        return MachineDtos.MachineView.of(machineService.untag(id, name));
    }

    /**
     * Manual "test connection": probes one of the caller's machines over SSH now and
     * returns it with the freshly observed status. On a reachable box it publishes a
     * {@code MachineReachedEvent} so the durable status refreshes to {@code ONLINE}
     * asynchronously. A not-owned or absent id is 404 (spec-019).
     */
    @POST
    @Path("/{id}/test")
    public MachineDtos.MachineView test(@PathParam("id") String id) {
        return MachineDtos.MachineView.of(connectionTestService.test(id));
    }
}
