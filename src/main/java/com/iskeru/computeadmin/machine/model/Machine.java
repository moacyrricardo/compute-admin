package com.iskeru.computeadmin.machine.model;

import com.iskeru.computeadmin.auth.model.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;
import org.hibernate.envers.RelationTargetAuditMode;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A registered SSH-reachable machine — the entity everything else operates on.
 * Owned by an {@link AppUser}; every {@code MachineService} operation scopes to
 * the current user, and a not-owned row reads as 404 (existence never leaked).
 *
 * <p>The first {@code @Audited} entity: revisions land in {@code machine_aud},
 * stamped with the ambient actor via the audit revision entity. The owner
 * relation is tracked with its target un-audited ({@link AppUser} is not
 * versioned config); the tag membership is {@link NotAudited} (labels only).
 *
 * <p>{@code pinnedHostKey} is reserved for TOFU host-key pinning (S3) and unused
 * for now — the app accepts any target host key.
 *
 * <p>spec-003.
 */
@Entity
@Table(name = "machine", uniqueConstraints = {
        @UniqueConstraint(name = "uq_machine_owner_host_port_user",
                columnNames = {"owner_id", "host", "port", "login_user"})
})
@Audited
@Getter
@Setter
@NoArgsConstructor
public class Machine {

    @Id
    @Column(length = 36)
    private String id = UUID.randomUUID().toString();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_machine_owner"))
    @Audited(targetAuditMode = RelationTargetAuditMode.NOT_AUDITED)
    private AppUser owner;

    @Column(nullable = false, length = 255)
    private String host;

    @Column(nullable = false)
    private int port = 22;

    @Column(name = "login_user", nullable = false, length = 255)
    private String loginUser;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MachineStatus status = MachineStatus.UNKNOWN;

    /** Reserved for host-key pinning (S3); unused until TOFU lands. */
    @Column(name = "pinned_host_key", length = 1024)
    private String pinnedHostKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /**
     * When the read-only OS/cloud facts probe last ran for this machine (spec-018).
     * Set once on the first successful reach; its presence is the guard that keeps
     * auto-tagging add-only and one-shot, so a user-removed auto-tag is never
     * re-added. {@link NotAudited} operational marker (a liveness-derived enrichment,
     * not a config edit) — no {@code machine_aud} column.
     */
    @Column(name = "facts_probed_at")
    @NotAudited
    private Instant factsProbedAt;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "machine_tag",
            joinColumns = @JoinColumn(name = "machine_id",
                    foreignKey = @ForeignKey(name = "fk_machine_tag_machine")),
            inverseJoinColumns = @JoinColumn(name = "tag_id",
                    foreignKey = @ForeignKey(name = "fk_machine_tag_tag")))
    @NotAudited
    private Set<Tag> tags = new HashSet<>();
}
