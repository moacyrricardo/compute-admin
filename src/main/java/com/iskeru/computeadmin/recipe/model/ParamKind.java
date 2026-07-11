package com.iskeru.computeadmin.recipe.model;

/**
 * How a {@link ParamDef} validates a supplied value — the whole of the parameter
 * gate (S4). {@link #ALLOWED_SET} is the strong shape (a closed set of literals);
 * {@link #REGEX} is the audited escape hatch and must be anchored;
 * {@link #INT_RANGE} bounds an integer.
 *
 * <p>spec-004.
 */
public enum ParamKind {
    ALLOWED_SET,
    REGEX,
    INT_RANGE
}
