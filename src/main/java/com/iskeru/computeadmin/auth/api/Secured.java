package com.iskeru.computeadmin.auth.api;

import jakarta.ws.rs.NameBinding;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Name-binding marker: a {@code *RS} class or method annotated {@code @Secured}
 * requires an authenticated caller. {@link AuthFilter} aborts such requests with
 * 401 when no {@link com.iskeru.computeadmin.common.AuthContext} is bound.
 *
 * <p>spec-011.
 */
@NameBinding
@Target({TYPE, METHOD})
@Retention(RUNTIME)
public @interface Secured {
}
