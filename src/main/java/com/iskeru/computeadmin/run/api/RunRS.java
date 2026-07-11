package com.iskeru.computeadmin.run.api;

import com.iskeru.computeadmin.auth.api.Secured;
import com.iskeru.computeadmin.run.service.RunOutputHub;
import com.iskeru.computeadmin.run.service.RunOutputHub.OutputEvent;
import com.iskeru.computeadmin.run.service.RunService;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.springframework.stereotype.Component;

/**
 * Per-user run engine over REST — the surface the UI drives (the MCP
 * {@code run_action} tool of spec 008 reaches the same {@link RunService}). Every
 * endpoint scopes to the current user: a not-owned or absent run id is a 404,
 * including the output stream, so one user can never read another's output.
 *
 * <p>{@code POST /} and {@code GET /{id}} return DTO records; {@code GET
 * /{id}/output} is the sanctioned streaming exception to that rule — it hands back
 * a live {@code text/event-stream} via JAX-RS {@link Sse}/{@link SseEventSink},
 * replaying any buffered prefix then the live tail and closing on completion.
 *
 * <p>spec-005.
 */
@Component
@Path("/runs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Secured
public class RunRS {

    private final RunService runService;

    public RunRS(RunService runService) {
        this.runService = runService;
    }

    @POST
    public RunDtos.RunView run(RunDtos.RunRequest body) {
        if (body == null) {
            throw new BadRequestException("body is required");
        }
        if (body.machineId() == null || body.machineId().isBlank()) {
            throw new BadRequestException("machineId is required");
        }
        if (body.actionId() == null || body.actionId().isBlank()) {
            throw new BadRequestException("actionId is required");
        }
        return RunDtos.RunView.of(runService.run(body.machineId(), body.actionId(), body.params()));
    }

    @GET
    @Path("/{id}")
    public RunDtos.RunView get(@PathParam("id") String id) {
        return RunDtos.RunView.of(runService.requireRun(id));
    }

    @GET
    @Path("/{id}/output")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void output(@PathParam("id") String id, @Context Sse sse, @Context SseEventSink eventSink) {
        // Ownership is checked (404) before any bytes are streamed.
        runService.subscribeToOutput(id, new RunOutputHub.Subscriber() {
            @Override
            public void onEvent(OutputEvent event) {
                if (!eventSink.isClosed()) {
                    eventSink.send(sse.newEventBuilder().name(event.stream()).data(event.data()).build());
                }
            }

            @Override
            public void onComplete() {
                if (!eventSink.isClosed()) {
                    eventSink.close();
                }
            }
        });
    }
}
