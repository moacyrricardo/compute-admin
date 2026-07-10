package com.iskeru.computeadmin.common;

/**
 * Escapes text for safe interpolation into hand-rendered HTML. All hand-rendered
 * HTML escaping goes through this utility (used once the UI renders JSON-driven
 * HTML in later specs).
 *
 * <p>spec-001.
 */
public final class HtmlEscaper {

    private HtmlEscaper() {
    }

    /** Escapes the five HTML-significant characters. Null becomes the empty string. */
    public static String escape(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length() + 16);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
