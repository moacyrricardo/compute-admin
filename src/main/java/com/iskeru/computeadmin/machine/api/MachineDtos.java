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

    /** {@code POST /api/machines} body. {@code port} defaults to 22 when null. */
    public record RegisterMachineRequest(String host, Integer port, String loginUser) {
    }

    /** {@code POST /api/machines/{id}/tags} body. */
    public record TagRequest(Set<String> names) {
    }

    /** A machine, safe to expose. Tags are sorted names. */
    public record MachineView(String id, String host, int port, String loginUser,
                              MachineStatus status, List<String> tags) {
        public static MachineView of(Machine machine) {
            Set<String> names = new TreeSet<>();
            for (Tag tag : machine.getTags()) {
                names.add(tag.getName());
            }
            return new MachineView(machine.getId(), machine.getHost(), machine.getPort(),
                    machine.getLoginUser(), machine.getStatus(), List.copyOf(names));
        }
    }
}
