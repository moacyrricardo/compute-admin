package com.iskeru.computeadmin.config;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Extracts a bearer credential from the {@code Authorization} header. Shared by
 * the two auth filters.
 *
 * <p>spec-011.
 */
final class BearerTokens {

    private static final String PREFIX = "Bearer ";

    private BearerTokens() {
    }

    /** The bearer value, or {@code null} when absent/malformed. */
    static String from(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(PREFIX)) {
            return null;
        }
        String value = header.substring(PREFIX.length()).trim();
        return value.isEmpty() ? null : value;
    }
}
