package com.iskeru.computeadmin.machine.service;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Machine registry service.
 *
 * <p><strong>Stub (spec-002).</strong> This proves the MCP seam — a tool
 * delegating to a feature service — before the registry exists. Spec 003
 * replaces the body with the real host/port/tags registry backed by
 * {@code MachineRepository}, at which point {@link #list} returns {@code Machine}
 * entities scoped to the caller. Until then it returns no machines.
 */
@Service
public class MachineService {

    /**
     * Lists registered machines, optionally filtered by tag.
     *
     * @param tag optional tag filter; {@code null} means "all"
     * @return the matching machines — always empty in the spec-002 stub
     */
    public List<String> list(String tag) {
        return List.of();
    }
}
