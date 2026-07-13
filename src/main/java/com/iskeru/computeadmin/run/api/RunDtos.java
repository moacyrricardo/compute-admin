package com.iskeru.computeadmin.run.api;

import com.iskeru.computeadmin.run.model.Run;
import com.iskeru.computeadmin.run.model.RunStatus;

import java.time.Instant;
import java.util.Map;

/**
 * DTO records for the {@code run} REST surface. The request record is plain; the
 * response record owns its mapping via a static {@code of(...)}. No mapper
 * framework.
 *
 * <p>{@code RunView} deliberately exposes lifecycle metadata (status, exit code,
 * timestamps) but not the captured stdout/stderr — output is delivered by the
 * streaming endpoint, and it may contain secrets the DB stores unredacted (a known
 * gap in spec-005).
 *
 * <p>spec-005.
 */
public final class RunDtos {

    private RunDtos() {
    }

    /** {@code POST /api/runs} body. {@code params} maps declared param names to values. */
    public record RunRequest(String machineId, String actionId, Map<String, String> params) {
    }

    /** A run's lifecycle view: which action/machine, status, exit code, timestamps. */
    public record RunView(String id, String machineId, String actionId, RunStatus status,
                          Integer exitCode, Instant createdAt, Instant startedAt, Instant finishedAt) {
        public static RunView of(Run run) {
            return new RunView(run.getId(), run.getMachine().getId(), run.getAction().getId(),
                    run.getStatus(), run.getExitCode(), run.getCreatedAt(),
                    run.getStartedAt(), run.getFinishedAt());
        }
    }

    /**
     * A fan-out child's minimal handle (spec-029): its id, the {@code appLabel} that
     * names the {@code (app-name, port)} item it ran, and its lifecycle status. The
     * fleet poll lists a parent's children to subscribe to each child's output stream
     * and attribute the per-app probe result to the right app card.
     */
    public record ChildRunView(String id, String appLabel, RunStatus status) {
        public static ChildRunView of(Run run) {
            return new ChildRunView(run.getId(), run.getAppLabel(), run.getStatus());
        }
    }
}
