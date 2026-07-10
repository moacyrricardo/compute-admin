package com.iskeru.computeadmin.common;

/**
 * The transport an inbound request arrived on — the ambient "who is acting" label
 * bound to the request scope and read through {@link CurrentActor}.
 *
 * <p>This is a <strong>placeholder</strong> that proves the ambient-actor seam
 * before users exist: it carries only the transport, not an authenticated
 * principal. Spec 011 replaces it with an identity-carrying
 * {@code ScopedValue<AuthContext>} + {@code CurrentUser} facade and renames
 * {@code Actor} → {@code Via}. Until then the label distinguishes transports for
 * audit and is <em>not</em> a security boundary on its own (see ARCH.md S8).
 *
 * <p>spec-002.
 */
public enum Actor {

    /** Request arrived on the {@code /api} REST surface (the web UI). */
    UI,

    /** Request arrived on the {@code /mcp} MCP transport (an agent). */
    MCP,

    /** No inbound request — a scheduled job or boot-time work with no actor. */
    SYSTEM
}
