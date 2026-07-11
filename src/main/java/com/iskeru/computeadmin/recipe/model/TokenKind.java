package com.iskeru.computeadmin.recipe.model;

/**
 * The kind of an {@link ArgToken} in an action's structured argv. A {@link
 * #LITERAL} contributes its {@code value} verbatim; a {@link #PARAM} names a
 * {@link ParamDef} whose validated, bound value is substituted at run time.
 *
 * <p>spec-004.
 */
public enum TokenKind {
    LITERAL,
    PARAM
}
