package com.iskeru.computeadmin.recipe.service;

/**
 * Thrown when a supplied parameter value fails its {@code ParamDef} rule (not a
 * member of the allowed set / does not match the anchored regex / integer out of
 * range), or when a required value is missing. Maps to <strong>HTTP 400</strong>.
 *
 * <p>This is the enforcement point of the parameter gate (S4): a value that does
 * not validate is never bound into the argv handed to the SSH adapter.
 *
 * <p>spec-004.
 */
public class ParamValidationException extends RuntimeException {

    public ParamValidationException(String message) {
        super(message);
    }
}
