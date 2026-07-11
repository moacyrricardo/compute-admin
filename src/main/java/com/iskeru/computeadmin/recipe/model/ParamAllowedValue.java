package com.iskeru.computeadmin.recipe.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * One permitted literal of an {@link ParamKind#ALLOWED_SET} {@link ParamDef}. A
 * supplied value binds only if it equals one of these — the strong shape of the
 * parameter gate (S4).
 *
 * <p>Not {@code @Audited} — captured indirectly by the action's approved snapshot
 * hash.
 *
 * <p>spec-004.
 */
@Entity
@Table(name = "param_allowed_value")
@Getter
@Setter
@NoArgsConstructor
public class ParamAllowedValue {

    @Id
    @Column(length = 36)
    private String id = UUID.randomUUID().toString();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "param_def_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_param_allowed_value_param_def"))
    private ParamDef paramDef;

    @Column(name = "allowed_value", nullable = false, length = 1024)
    private String value;
}
