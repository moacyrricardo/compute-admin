package com.iskeru.computeadmin.machine.api;

import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.machine.model.MachineStatus;
import com.iskeru.computeadmin.machine.model.Tag;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * DTO records for the {@code machine} REST surface. Request records are plain;
 * response records own their mapping via a static {@code of(...)}. No mapper
 * framework.
 *
 * <p>spec-003.
 */
public final class MachineDtos {

    private MachineDtos() {
    }

    /**
     * {@code POST /api/machines} body. {@code name} is required; {@code port}
     * defaults to 22 when null.
     */
    public record RegisterMachineRequest(String name, String host, Integer port, String loginUser) {
    }

    /** {@code POST /api/machines/{id}/tags} body. */
    public record TagRequest(Set<String> names) {
    }

    /**
     * The <strong>UI</strong> view — the full detail an authenticated human in
     * their own browser session sees, including the SSH coordinates they registered.
     * Tags are sorted names. Contrast {@link McpMachineView}, which omits the infra.
     *
     * <p>spec-003; {@code name} added in spec-028.
     */
    public record MachineView(String id, String name, String host, int port, String loginUser,
                              MachineStatus status, List<String> tags) {
        public static MachineView of(Machine machine) {
            return new MachineView(machine.getId(), machine.getName(), machine.getHost(),
                    machine.getPort(), machine.getLoginUser(), machine.getStatus(),
                    sortedTagNames(machine));
        }
    }

    /**
     * The <strong>MCP</strong> view — what flows over MCP into an LLM. Exposes
     * {@code id + name + status + tags} <em>only</em>; it deliberately has no
     * {@code host}/{@code port}/{@code loginUser} accessor, so the omission is
     * structural (a distinct type), not a serialization filter that could regress
     * (spec-028, resolving ARCH S9).
     *
     * <p>spec-028.
     */
    public record McpMachineView(String id, String name, MachineStatus status, List<String> tags) {
        public static McpMachineView of(Machine machine) {
            return new McpMachineView(machine.getId(), machine.getName(),
                    machine.getStatus(), sortedTagNames(machine));
        }
    }

    private static List<String> sortedTagNames(Machine machine) {
        Set<String> names = new TreeSet<>();
        for (Tag tag : machine.getTags()) {
            names.add(tag.getName());
        }
        return List.copyOf(names);
    }
}
