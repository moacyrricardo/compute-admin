package com.iskeru.computeadmin.recipe.model;

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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.envers.Audited;
import org.hibernate.envers.RelationTargetAuditMode;

import java.time.Instant;
import java.util.UUID;

/**
 * A named collection of {@link Action}s scoped to exactly <strong>one</strong>
 * {@link Machine}. Ownership derives through {@code machine.owner} (spec 011): a
 * recipe belongs to whoever owns its machine, and every {@code RecipeService}
 * operation scopes to the current user (a not-owned id reads as 404).
 *
 * <p>Cross-machine reuse comes from blueprints (spec 010), which instantiate a
 * per-machine recipe; {@code sourceBlueprintId}/{@code sourceBlueprintVersion}
 * record that provenance and are null for a hand-authored recipe.
 *
 * <p>{@code @Audited}: revisions land in {@code recipe_aud}. {@code description}
 * is free display text — never executed.
 *
 * <p>spec-004.
 */
@Entity
@Table(name = "recipe")
@Audited
@Getter
@Setter
@NoArgsConstructor
public class Recipe {

    @Id
    @Column(length = 36)
    private String id = UUID.randomUUID().toString();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "machine_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_recipe_machine"))
    @Audited(targetAuditMode = RelationTargetAuditMode.NOT_AUDITED)
    private Machine machine;

    @Column(nullable = false, length = 255)
    private String name;

    /** Free display text describing the recipe. Never executed. */
    @Column(length = 2000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RecipeType type = RecipeType.CUSTOM;

    /** Blueprint this recipe was instantiated from (spec 010); null if authored directly. */
    @Column(name = "source_blueprint_id", length = 36)
    private String sourceBlueprintId;

    /** Version of the source blueprint at instantiation (spec 010); null if authored directly. */
    @Column(name = "source_blueprint_version")
    private Integer sourceBlueprintVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
