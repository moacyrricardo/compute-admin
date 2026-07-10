package com.iskeru.computeadmin.audit;

import com.iskeru.computeadmin.common.AuthContext;
import com.iskeru.computeadmin.common.CurrentUser;
import com.iskeru.computeadmin.common.Via;
import org.hibernate.envers.RevisionListener;

/**
 * Stamps each new {@link AuditRevision} with the ambient actor read from
 * {@link CurrentUser}: the caller's {@code userId} (null when unbound or running
 * as {@link Via#SYSTEM}) and the transport {@code via}, defaulting to
 * {@link Via#SYSTEM} for unattended work (scheduled connectivity checks).
 *
 * <p>Instantiated by Envers (not Spring), so it reaches the actor only through the
 * static {@link CurrentUser} facade.
 *
 * <p>spec-003.
 */
public class CurrentUserRevisionListener implements RevisionListener {

    @Override
    public void newRevision(Object revisionEntity) {
        AuditRevision revision = (AuditRevision) revisionEntity;
        revision.setUserId(CurrentUser.userIdOrSystem());
        revision.setVia(CurrentUser.optional().map(AuthContext::via).orElse(Via.SYSTEM));
    }
}
