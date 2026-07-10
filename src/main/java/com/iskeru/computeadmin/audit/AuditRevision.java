package com.iskeru.computeadmin.audit;

import com.iskeru.computeadmin.common.Via;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.envers.RevisionEntity;
import org.hibernate.envers.RevisionNumber;
import org.hibernate.envers.RevisionTimestamp;

/**
 * The Envers revision entity backing the {@code revinfo} table. Every audited
 * write ({@code Machine} in this spec; {@code Recipe}/{@code Action} later) opens
 * a revision here, stamped by {@link CurrentUserRevisionListener} with the ambient
 * actor: the acting {@code userId} (nullable) and the transport {@code via}.
 *
 * <p>Because {@code ddl-auto=none}, the {@code revinfo} table is created by hand
 * in {@code V3__machine.sql} — the same migration as the first audited entity.
 *
 * <p>spec-003.
 */
@Entity
@Table(name = "revinfo")
@RevisionEntity(CurrentUserRevisionListener.class)
@Getter
@Setter
@NoArgsConstructor
public class AuditRevision {

    @Id
    @RevisionNumber
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "rev")
    private long rev;

    @RevisionTimestamp
    @Column(name = "timestamp", nullable = false)
    private long timestamp;

    /** The acting user's id, or {@code null} for a {@link Via#SYSTEM} revision. */
    @Column(name = "user_id", length = 36)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "via", length = 20)
    private Via via;
}
