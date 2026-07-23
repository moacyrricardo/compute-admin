package com.iskeru.computeadmin.recipe.model;

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
 * One element of an {@link Action}'s structured argv, held at an explicit
 * {@code position} and loaded ordered by it. A {@link TokenKind#LITERAL} carries
 * its text in {@code value}; a {@link TokenKind#PARAM} carries the name of the
 * {@link ParamDef} whose bound value is substituted. Modelling the command as
 * discrete tokens (never a shell line) is what keeps binding injection-safe (S4).
 *
 * <p>Not {@code @Audited} — captured indirectly by the action's approved snapshot
 * hash.
 *
 * <p>spec-004.
 */
@Entity
@Table(name = "arg_token")
@Getter
@Setter
@NoArgsConstructor
public class ArgToken {

    @Id
    @Column(length = 36)
    private String id = UUID.randomUUID().toString();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "action_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_arg_token_action"))
    private Action action;

    @Column(nullable = false)
    private int position;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TokenKind kind;

    /**
     * Literal text (LITERAL) or the referenced param name (PARAM). Widened in spec-049
     * (migration V15) to hold a longer fixed probe script (the app-folder/footprint walk).
     */
    @Column(name = "token_value", nullable = false, length = 16384)
    private String value;
}
