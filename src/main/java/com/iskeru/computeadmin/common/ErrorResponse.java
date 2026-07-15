package com.iskeru.computeadmin.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The single JSON error body for the whole app: {@code {"error":"<snake_code>"}},
 * plus an optional {@code {"detail":…}} that is omitted (via {@link JsonInclude})
 * when {@code null}. Replaces the per-mapper {@code Map.of("error", …)} bodies so
 * every {@link AppException} produces one typed shape.
 *
 * <p>spec-046.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String error, String detail) {

    /** Body with a code and no detail — the common 404/409/401/400 case. */
    public static ErrorResponse of(String error) {
        return new ErrorResponse(error, null);
    }
}
