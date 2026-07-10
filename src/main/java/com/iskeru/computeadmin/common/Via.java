package com.iskeru.computeadmin.common;

/**
 * The transport an authenticated request arrived on — the {@code via} facet of
 * the ambient {@link AuthContext}. Recorded on audited writes and on {@code Run}
 * rows so history distinguishes a UI action from an agent's.
 *
 * <p>Supersedes spec-002's placeholder {@code Actor}: that enum stood in before
 * users existed and carried only the transport. It now travels <em>alongside</em>
 * an authenticated principal in {@link AuthContext}, no longer on its own.
 *
 * <p>spec-011.
 */
public enum Via {

    /** Request authenticated by the app JWT on the {@code /api} REST surface. */
    UI,

    /** Request authenticated by a personal token on the {@code /mcp} transport. */
    MCP,

    /** No inbound request — a scheduled job or boot-time work with no user. */
    SYSTEM
}
