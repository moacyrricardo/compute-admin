package com.iskeru.computeadmin.blueprint.model;

import com.iskeru.computeadmin.recipe.model.TokenKind;
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

import java.util.UUID;

/**
 * One element of a {@link BlueprintAction}'s structured argv, held at an explicit
 * {@code position}. Mirrors {@code recipe} {@code ArgToken} (reusing its
 * {@link TokenKind}); instantiation copies each token verbatim into the per-machine
 * action's argv. Not {@code @Audited} — captured indirectly by the instantiated
 * action's approved snapshot hash.
 *
 * <p>spec-010.
 */
@Entity
@Table(name = "blueprint_arg_token")
@Getter
@Setter
@NoArgsConstructor
public class BlueprintArgToken {

    @Id
    @Column(length = 36)
    private String id = UUID.randomUUID().toString();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "blueprint_action_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_blueprint_arg_token_action"))
    private BlueprintAction blueprintAction;

    @Column(nullable = false)
    private int position;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TokenKind kind;

    /**
     * Literal text (LITERAL) or the referenced param name (PARAM). Widened in spec-049
     * (migration V15) to stay symmetric with {@code arg_token.token_value}.
     */
    @Column(name = "token_value", nullable = false, length = 16384)
    private String value;
}
