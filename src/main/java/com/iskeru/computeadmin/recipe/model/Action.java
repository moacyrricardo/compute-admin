package com.iskeru.computeadmin.recipe.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;
import org.hibernate.envers.RelationTargetAuditMode;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The runnable unit: a structured argv ({@link ArgToken}s) plus a typed parameter
 * schema ({@link ParamDef}s) plus a {@code sudo} flag and an approval state. This
 * is the entity the gate protects — only an {@link ApprovalState#APPROVED} action
 * runs, and only via the shared run path (spec 005).
 *
 * <p>Ownership derives through {@code recipe.machine.owner} (spec 011). Approval
 * binds to {@code approvedSnapshotHash} — the content hash of the argv/params/sudo
 * at approval time (see {@code ActionSnapshot}); any structural edit resets the
 * state to {@link ApprovalState#DRAFT} and clears the hash, defeating
 * approve-then-mutate (TOCTOU).
 *
 * <p>{@code @Audited}: revisions land in {@code action_aud}, so the approval
 * transition is versioned. The {@code argTokens}/{@code paramDefs} collections are
 * {@link NotAudited} (their content is captured indirectly by the audited
 * snapshot hash). {@code description} is free display text — what a human reads
 * when approving, never executed.
 *
 * <p>spec-004.
 */
@Entity
@Table(name = "action")
@Audited
@Getter
@Setter
@NoArgsConstructor
public class Action {

    @Id
    @Column(length = 36)
    private String id = UUID.randomUUID().toString();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipe_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_action_recipe"))
    @Audited(targetAuditMode = RelationTargetAuditMode.NOT_AUDITED)
    private Recipe recipe;

    @Column(nullable = false, length = 255)
    private String name;

    /** Free display text — what a human reads when approving. Never executed. */
    @Column(length = 2000)
    private String description;

    @Column(nullable = false)
    private boolean sudo;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_state", nullable = false, length = 20)
    private ApprovalState approvalState = ApprovalState.DRAFT;

    /** Content hash bound at approval (see {@code ActionSnapshot}); null unless APPROVED. */
    @Column(name = "approved_snapshot_hash", length = 64)
    private String approvedSnapshotHash;

    /** SHA-256 of the pinned script's bytes at approval (CUSTOM actions); null otherwise. spec-015. */
    @Column(name = "approved_script_hash", length = 64)
    private String approvedScriptHash;

    @Column(name = "approved_at")
    private Instant approvedAt;

    /** The user who approved (always via UI); null unless approved. */
    @Column(name = "approved_by_user_id", length = 36)
    private String approvedByUserId;

    @OneToMany(mappedBy = "action", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("position ASC")
    @NotAudited
    private Set<ArgToken> argTokens = new LinkedHashSet<>();

    @OneToMany(mappedBy = "action", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("name ASC")
    @NotAudited
    private Set<ParamDef> paramDefs = new LinkedHashSet<>();
}
