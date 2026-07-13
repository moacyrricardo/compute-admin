package com.iskeru.computeadmin.recipe.service;

/**
 * Thrown at <em>approval</em> when a {@code CUSTOM} action's wrapped script cannot be
 * hashed over SSH — the file is missing, {@code sha256sum} is absent, or it is not
 * readable even with the action's {@code sudo}. Maps to <strong>HTTP 409</strong>
 * (the target's state prevents pinning). Approval is refused rather than silently
 * left unpinned, so a {@code CUSTOM} action is either pinned or not approved.
 *
 * <p>spec-015.
 */
public class ScriptUnreadableException extends RuntimeException {

    public ScriptUnreadableException(String id) {
        super("Script could not be read to pin at approval: " + id);
    }
}
