package com.iskeru.computeadmin.recipe.model;

/**
 * The approval lifecycle of an {@link Action} — the core of the gate. Only
 * {@link #APPROVED} is runnable (enforced on the run path, spec 005). The
 * transition into {@code APPROVED} is reachable only from REST
 * ({@code ActionRS}), never from the {@code mcp} module.
 *
 * <pre>
 *   DRAFT ── submit ──▶ PENDING_APPROVAL ── approve ──▶ APPROVED
 *                              │                            │
 *                              └────────── revoke ──────────┴──▶ REVOKED
 * </pre>
 *
 * <p>Any structural edit drops an action back to {@link #DRAFT}, so an action
 * cannot be approved benign then mutated (TOCTOU).
 *
 * <p>spec-004.
 */
public enum ApprovalState {
    DRAFT,
    PENDING_APPROVAL,
    APPROVED,
    REVOKED
}
