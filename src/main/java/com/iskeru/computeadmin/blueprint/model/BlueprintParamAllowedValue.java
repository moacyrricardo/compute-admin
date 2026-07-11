package com.iskeru.computeadmin.blueprint.model;

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
 * One permitted literal of an {@code ALLOWED_SET} {@link BlueprintParamDef}, copied
 * verbatim into the per-machine action on instantiation. Mirrors {@code recipe}
 * {@code ParamAllowedValue}. Not {@code @Audited}.
 *
 * <p>spec-010.
 */
@Entity
@Table(name = "blueprint_param_allowed_value")
@Getter
@Setter
@NoArgsConstructor
public class BlueprintParamAllowedValue {

    @Id
    @Column(length = 36)
    private String id = UUID.randomUUID().toString();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "blueprint_param_def_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_blueprint_param_allowed_value_def"))
    private BlueprintParamDef paramDef;

    @Column(name = "allowed_value", nullable = false, length = 1024)
    private String value;
}
