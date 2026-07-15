package com.iskeru.computeadmin.recipe.service;

import com.iskeru.computeadmin.common.AppException;
import jakarta.ws.rs.core.Response;

/**
 * Thrown at <em>approval</em> when a {@code CUSTOM} action's wrapped script cannot be
 * hashed over SSH — the file is missing, {@code sha256sum} is absent, or it is not
 * readable even with the action's {@code sudo}. Maps to <strong>HTTP 409</strong>
 * {@code {"error":"script_unreadable"}} (the target's state prevents pinning).
 * Approval is refused rather than silently left unpinned, so a {@code CUSTOM} action
 * is either pinned or not approved.
 *
 * <p>spec-015; carries its own response since spec-046.
 */
public class ScriptUnreadableException extends AppException {

    public ScriptUnreadableException(String id) {
        super(Response.Status.CONFLICT, "script_unreadable");
    }
}
