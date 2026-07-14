package com.iskeru.computeadmin.discovery.model;

import com.iskeru.computeadmin.machine.model.Machine;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A per-{@code (machine, family)} discovery-enablement override (spec-035): whether a
 * given {@link DiscovererFamily} may probe a machine. A machine with no row for a family
 * falls back to that family's {@link DiscovererFamily#defaultEnabled() account-level
 * default} (docker off, everything else on), so only deviations are stored.
 *
 * <p>Ownership is derived through {@link #machine} (as {@code Recipe}/{@code Action}
 * derive theirs) — the enablement service scopes every read/write to the current user's
 * machines. It is an operational capability toggle, not versioned config, so it is
 * <strong>not</strong> {@code @Audited} (no {@code _aud} table) — cf. {@code Run}.
 *
 * <p>spec-035.
 */
@Entity
@Table(name = "discovery_enablement", uniqueConstraints = {
        @UniqueConstraint(name = "uq_discovery_enablement_machine_family",
                columnNames = {"machine_id", "family"})
})
@Getter
@Setter
@NoArgsConstructor
public class DiscoveryEnablement {

    @Id
    @Column(length = 36)
    private String id = UUID.randomUUID().toString();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "machine_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_discovery_enablement_machine"))
    private Machine machine;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DiscovererFamily family;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
