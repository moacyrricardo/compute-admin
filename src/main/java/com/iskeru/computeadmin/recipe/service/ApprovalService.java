package com.iskeru.computeadmin.recipe.service;

import com.iskeru.computeadmin.common.CurrentUser;
import com.iskeru.computeadmin.recipe.model.Action;
import com.iskeru.computeadmin.recipe.model.ApprovalState;
import com.iskeru.computeadmin.recipe.repository.ActionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * The approval state machine — the heart of the gate. It is deliberately
 * <strong>REST-only and UI-only</strong>: it is reached only from {@code ActionRS}
 * (a {@code @Secured} resource, so {@code via = UI}), and the {@code mcp} module
 * never references it. There is no approve tool, so an agent has no path to
 * approve — enforced structurally and asserted by {@code GateArchTest}.
 *
 * <p>Every transition scopes to the current user through {@link
 * ActionService#requireAction(String)}: a user may act only on his own actions,
 * and another user's action reads as a 404 ({@link ActionNotFoundException}).
 *
 * <pre>
 *   submitForApproval:  DRAFT                     → PENDING_APPROVAL
 *   approve:            PENDING_APPROVAL          → APPROVED   (binds snapshot hash + approver)
 *   revoke:             APPROVED | PENDING_APPROVAL → REVOKED
 * </pre>
 *
 * <p>spec-004.
 */
@Service
public class ApprovalService {

    private final ActionService actionService;
    private final ActionRepository actions;

    public ApprovalService(ActionService actionService, ActionRepository actions) {
        this.actionService = actionService;
        this.actions = actions;
    }

    /** {@code DRAFT → PENDING_APPROVAL}. */
    @Transactional
    public Action submitForApproval(String actionId) {
        Action action = actionService.requireAction(actionId);
        if (action.getApprovalState() != ApprovalState.DRAFT) {
            throw new IllegalApprovalTransitionException(action.getApprovalState(), "submit");
        }
        action.setApprovalState(ApprovalState.PENDING_APPROVAL);
        return actions.save(action);
    }

    /**
     * {@code PENDING_APPROVAL → APPROVED}. Binds {@code approvedSnapshotHash} to the
     * current content hash, and records {@code approvedAt} and the approver
     * ({@code approvedByUserId = } current user). Reachable only from REST/UI.
     */
    @Transactional
    public Action approve(String actionId) {
        Action action = actionService.requireAction(actionId);
        if (action.getApprovalState() != ApprovalState.PENDING_APPROVAL) {
            throw new IllegalApprovalTransitionException(action.getApprovalState(), "approve");
        }
        action.setApprovalState(ApprovalState.APPROVED);
        action.setApprovedSnapshotHash(ActionSnapshot.hash(action));
        action.setApprovedAt(Instant.now());
        action.setApprovedByUserId(CurrentUser.require().userId());
        return actions.save(action);
    }

    /** {@code APPROVED | PENDING_APPROVAL → REVOKED}. */
    @Transactional
    public Action revoke(String actionId) {
        Action action = actionService.requireAction(actionId);
        ApprovalState state = action.getApprovalState();
        if (state != ApprovalState.APPROVED && state != ApprovalState.PENDING_APPROVAL) {
            throw new IllegalApprovalTransitionException(state, "revoke");
        }
        action.setApprovalState(ApprovalState.REVOKED);
        action.setApprovedSnapshotHash(null);
        action.setApprovedAt(null);
        action.setApprovedByUserId(null);
        return actions.save(action);
    }
}
