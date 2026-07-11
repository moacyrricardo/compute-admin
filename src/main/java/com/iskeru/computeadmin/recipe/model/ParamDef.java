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
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The typed validation rule for one named parameter of an {@link Action} — the
 * whole of the parameter gate (S4). {@link ParamKind#ALLOWED_SET} closes the value
 * to a set of literals ({@link ParamAllowedValue}); {@link ParamKind#REGEX} bounds
 * it to an anchored {@code pattern}; {@link ParamKind#INT_RANGE} bounds an integer
 * to {@code [intMin, intMax]}. Enum-style params are {@code ALLOWED_SET}.
 *
 * <p>Not {@code @Audited} — captured indirectly by the action's approved snapshot
 * hash.
 *
 * <p>spec-004.
 */
@Entity
@Table(name = "param_def")
@Getter
@Setter
@NoArgsConstructor
public class ParamDef {

    @Id
    @Column(length = 36)
    private String id = UUID.randomUUID().toString();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "action_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_param_def_action"))
    private Action action;

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
    private Set<ParamAllowedValue> allowedValues = new LinkedHashSet<>();
}
