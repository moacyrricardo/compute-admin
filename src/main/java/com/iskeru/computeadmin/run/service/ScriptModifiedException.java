package com.iskeru.computeadmin.run.service;

/**
 * Thrown when a pinned {@code CUSTOM} action's <em>live</em> script bytes no longer
 * match the {@code approvedScriptHash} bound at approval — i.e. the on-box script was
 * swapped after approval but before it ran — or the script has become unreadable.
 * Maps to <strong>HTTP 409</strong>.
 *
 * <p>The content-pinning analogue of {@link ActionModifiedException}: where that gate
 * re-verifies the structural definition, this one re-verifies the wrapped script's
 * bytes, closing the approve-then-run TOCTOU window for the file a {@code CUSTOM}
 * action actually executes. A drifted, missing, or unreadable script never runs.
 *
 * <p>spec-015.
 */
public class ScriptModifiedException extends RuntimeException {

    public ScriptModifiedException(String id) {
        super("Script modified since approval: " + id);
    }
}
