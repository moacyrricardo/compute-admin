package com.iskeru.computeadmin.common;

/**
 * Cross-cutting DTO records. One {@code *Dtos} final class per api-ish package,
 * private ctor, holding nested {@code record}s (no mapper framework).
 *
 * <p>spec-001.
 */
public final class CommonDtos {

    private CommonDtos() {
    }

    /** Health probe response. */
    public record Health(String status, String version) {
    }
}
