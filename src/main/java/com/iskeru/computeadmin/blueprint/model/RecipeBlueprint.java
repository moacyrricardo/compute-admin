package com.iskeru.computeadmin.blueprint.model;

import com.iskeru.computeadmin.auth.model.AppUser;
import com.iskeru.computeadmin.recipe.model.RecipeType;
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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.envers.Audited;
import org.hibernate.envers.RelationTargetAuditMode;

import java.time.Instant;
import java.util.UUID;

/**
 * A machine-independent recipe definition authored once and instantiated onto many
 * machines (spec 010). It has the same display shape as a {@code Recipe} but,
 * deliberately, <strong>no machine, no approval state and no run path</strong>: a
 * blueprint is never runnable. Instantiation ({@code InstantiationService}) copies
 * it into ordinary per-machine {@code Recipe}/{@code Action} rows, and each
 * instantiated action is approved per-machine through the 004 gate — authoring is
 * shared, approval never is.
 *
 * <p>Owned by an {@link AppUser} (blueprints are per-user, never shared);
 * {@code BlueprintService}/{@code InstantiationService} scope every operation to
 * the current user, and a not-owned row reads as 404. {@code version} starts at 1
 * and is bumped on every content edit — instantiated recipes record the version
 * they were copied from as provenance.
 *
 * <p>{@code @Audited}: revisions land in {@code recipe_blueprint_aud}.
 * {@code description} is free display text — never executed.
 *
 * <p>spec-010.
 */
@Entity
@Table(name = "recipe_blueprint")
@Audited
@Getter
@Setter
@NoArgsConstructor
public class RecipeBlueprint {

    @Id
    @Column(length = 36)
    private String id = UUID.randomUUID().toString();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_recipe_blueprint_owner"))
    @Audited(targetAuditMode = RelationTargetAuditMode.NOT_AUDITED)
    private AppUser owner;

    @Column(nullable = false, length = 255)
    private String name;

    /** Free display text describing the blueprint. Never executed. */
    @Column(length = 2000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RecipeType type = RecipeType.CUSTOM;

    /** Bumped on every content edit (starts at 1); recorded on each instantiated recipe. */
    @Column(nullable = false)
    private int version = 1;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
