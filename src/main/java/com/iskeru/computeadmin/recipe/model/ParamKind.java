package com.iskeru.computeadmin.recipe.model;

/**
 * How a {@link ParamDef} validates a supplied value — the whole of the parameter
 * gate (S4). {@link #ALLOWED_SET} is the strong shape (a closed set of literals);
 * {@link #REGEX} is the audited escape hatch and must be anchored;
 * {@link #INT_RANGE} bounds an integer.
 *
 * <p>{@link #APP_PORT_LIST} is a <strong>repeatable composite</strong> used only by
 * monitor actions (spec-022): a runtime list of {@code (appName, port)} items, each
 * item validated against a <em>fixed</em> item schema — {@code appName} against the
 * anchored constant {@link com.iskeru.computeadmin.recipe.service.ParamBinder#APP_NAME_PATTERN}
 * and {@code port} against {@code [1, 65535]}. It is deliberately narrow (a fixed
 * item schema, not a general "list of records" kind) to keep the S4 surface small.
 * The item list is a <strong>runtime value</strong>, never part of the action's
 * content hash; the fan-out that runs the fixed single-app template once per item
 * lives in {@code RunService}, never in a shell loop.
 *
 * <p>spec-004; {@code APP_PORT_LIST} added in spec-022.
 */
public enum ParamKind {
    ALLOWED_SET,
    REGEX,
    INT_RANGE,
    APP_PORT_LIST
}
