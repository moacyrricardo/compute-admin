package com.iskeru.computeadmin.common;

/**
 * The authenticated caller bound to the current request scope: who they are
 * ({@code userId}, {@code email}) and which transport they arrived on
 * ({@code via}). Bound by exactly one auth filter per surface and read only
 * through {@link CurrentUser}.
 *
 * <p>For a {@link Via#SYSTEM} context (scheduled jobs) {@code userId} and
 * {@code email} are {@code null}.
 *
 * <p>spec-011.
 */
public record AuthContext(String userId, String email, Via via) {

    /** A UI-authenticated context (app JWT from email+password sign-in). */
    public static AuthContext ui(String userId, String email) {
        return new AuthContext(userId, email, Via.UI);
    }

    /** An MCP-authenticated context (personal token). */
    public static AuthContext mcp(String userId, String email) {
        return new AuthContext(userId, email, Via.MCP);
    }

    /** The unattended system context used by scheduled work. */
    public static AuthContext system() {
        return new AuthContext(null, null, Via.SYSTEM);
    }
}
