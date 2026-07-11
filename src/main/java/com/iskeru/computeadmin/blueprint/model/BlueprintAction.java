package com.iskeru.computeadmin.blueprint.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A blueprint's action definition — the same command shape as a {@code recipe}
 * {@code Action} (a structured argv via {@link BlueprintArgToken}s plus a typed
 * parameter schema via {@link BlueprintParamDef}s and a {@code sudo} flag) but with
 * <strong>no approval state and no run path</strong>. Instantiation copies it into
 * a per-machine {@code Action} that carries the approval state; the blueprint action
 * itself is never runnable (spec 010).
 *
 * <p>{@code @Audited}: revisions land in {@code blueprint_action_aud}. The
 * {@code argTokens}/{@code paramDefs} collections are {@link NotAudited} (mirroring
 * {@code Action}). {@code description} is free display text — never executed.
 *
 * <p>spec-010.
 */
@Entity
@Table(name = "blueprint_action")
@Audited
@Getter
@Setter
@NoArgsConstructor
public class BlueprintAction {

    @Id
    @Column(length = 36)
    private String id = UUID.randomUUID().toString();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "blueprint_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_blueprint_action_blueprint"))
    @Audited(targetAuditMode = RelationTargetAuditMode.NOT_AUDITED)
    private RecipeBlueprint blueprint;

    @Column(nullable = false, length = 255)
    private String name;

    /** Free display text — what a human reads when approving an instance. Never executed. */
    @Column(length = 2000)
    private String description;

    @Column(nullable = false)
    private boolean sudo;

    @OneToMany(mappedBy = "blueprintAction", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("position ASC")
    @NotAudited
    private Set<BlueprintArgToken> argTokens = new LinkedHashSet<>();

    @OneToMany(mappedBy = "blueprintAction", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("name ASC")
    @NotAudited
    private Set<BlueprintParamDef> paramDefs = new LinkedHashSet<>();
}
