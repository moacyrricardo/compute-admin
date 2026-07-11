package com.iskeru.computeadmin.blueprint.model;

import com.iskeru.computeadmin.recipe.model.ParamKind;
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
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The typed validation rule for one named parameter of a {@link BlueprintAction}.
 * Mirrors {@code recipe} {@code ParamDef} (reusing its {@link ParamKind}):
 * {@link ParamKind#ALLOWED_SET} closes the value to a set of literals
 * ({@link BlueprintParamAllowedValue}); {@link ParamKind#REGEX} bounds it to an
 * anchored {@code pattern}; {@link ParamKind#INT_RANGE} bounds an integer to
 * {@code [intMin, intMax]}. Instantiation copies the rule verbatim into the
 * per-machine action. Not {@code @Audited}.
 *
 * <p>spec-010.
 */
@Entity
@Table(name = "blueprint_param_def")
@Getter
@Setter
@NoArgsConstructor
public class BlueprintParamDef {

    @Id
    @Column(length = 36)
    private String id = UUID.randomUUID().toString();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "blueprint_action_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_blueprint_param_def_action"))
    private BlueprintAction blueprintAction;

    @Column(nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ParamKind kind;

    /** Anchored regular expression (REGEX only); null otherwise. */
    @Column(length = 1024)
    private String pattern;

    /** Inclusive lower bound (INT_RANGE only); null otherwise. */
    @Column(name = "int_min")
    private Integer intMin;

    /** Inclusive upper bound (INT_RANGE only); null otherwise. */
    @Column(name = "int_max")
    private Integer intMax;

    @OneToMany(mappedBy = "paramDef", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private Set<BlueprintParamAllowedValue> allowedValues = new LinkedHashSet<>();
}
