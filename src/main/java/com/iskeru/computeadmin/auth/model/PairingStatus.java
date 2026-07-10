package com.iskeru.computeadmin.auth.model;

/**
 * Lifecycle of an MCP self-setup {@link PairingRequest}. A request is born
 * {@code PENDING}; a human {@code APPROVED}/{@code DENIED} it in the UI; it lapses
 * to {@code EXPIRED} once past its deadline; and it becomes {@code CONSUMED} after
 * a poll has taken the minted token exactly once.
 *
 * <p>spec-011.
 */
public enum PairingStatus {
    PENDING,
    APPROVED,
    DENIED,
    EXPIRED,
    CONSUMED
}
