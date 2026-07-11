package com.iskeru.computeadmin.recipe.service;

import com.iskeru.computeadmin.recipe.model.ApprovalState;

/**
 * Thrown when an approval transition is requested from a state that does not
 * permit it (e.g. approving an action that was never submitted, or submitting one
 * already APPROVED). Maps to <strong>HTTP 409</strong>.
 *
 * <p>spec-004.
 */
public class IllegalApprovalTransitionException extends RuntimeException {

    public IllegalApprovalTransitionException(ApprovalState from, String transition) {
        super("Illegal approval transition '" + transition + "' from state " + from);
    }
}
