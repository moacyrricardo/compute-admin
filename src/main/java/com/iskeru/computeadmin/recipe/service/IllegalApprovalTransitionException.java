package com.iskeru.computeadmin.recipe.service;

import com.iskeru.computeadmin.common.AppException;
import com.iskeru.computeadmin.recipe.model.ApprovalState;
import jakarta.ws.rs.core.Response;

/**
 * Thrown when an approval transition is requested from a state that does not
 * permit it (e.g. approving an action that was never submitted, or submitting one
 * already APPROVED). Maps to <strong>HTTP 409</strong> {@code
 * {"error":"illegal_approval_transition"}}.
 *
 * <p>spec-004; carries its own response since spec-046.
 */
public class IllegalApprovalTransitionException extends AppException {

    public IllegalApprovalTransitionException(ApprovalState from, String transition) {
        super(Response.Status.CONFLICT, "illegal_approval_transition");
    }
}
