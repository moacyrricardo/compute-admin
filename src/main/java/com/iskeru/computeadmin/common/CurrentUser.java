package com.iskeru.computeadmin.common;

import java.util.Optional;

/**
 * Static facade over the request-scoped {@link AuthContext}. Application code
 * reads the acting caller <strong>only</strong> through this facade — the raw
 * {@link java.lang.ScopedValue} holder is package-private and bound solely by an
 * auth filter via {@link #runWhere}.
 *
 * <p>Supersedes spec-002's {@code CurrentActor}: it now carries an authenticated
 * principal ({@code userId}/{@code email}), not just a transport label. Services
 * scope every read and write to {@link #require()}; the Envers listener stamps
 * {@link #userIdOrSystem()} + {@link #via()} onto each revision.
 *
 * <p>spec-011.
 */
public final class CurrentUser {

    /**
     * The request-scoped auth context. Package-private on purpose: no application
     * code may touch the raw scope; it reads through the accessors below and
     * binding happens only through {@link #runWhere}.
     */
    static final ScopedValue<AuthContext> CURRENT = ScopedValue.newInstance();

    private CurrentUser() {
    }

    /**
     * The authenticated caller bound to the current scope.
     *
     * @throws UnauthorizedException (401) if no caller is bound — the request is
     *                               unauthenticated or ran off a bound thread.
     */
    public static AuthContext require() {
        if (!CURRENT.isBound()) {
            throw new UnauthorizedException();
        }
        return CURRENT.get();
    }

    /** The caller bound to the current scope, or empty when unbound. */
    public static Optional<AuthContext> optional() {
        return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
    }

    /** The current user's id; throws 401 if unbound (see {@link #require()}). */
    public static String userId() {
        return require().userId();
    }

    /** The current user's email; throws 401 if unbound. */
    public static String email() {
        return require().email();
    }

    /** The current transport; throws 401 if unbound. */
    public static Via via() {
        return require().via();
    }

    /**
     * The current user's id, or {@code null} when unbound or running as
     * {@link Via#SYSTEM}. The audit listener uses this so unattended writes stamp
     * a null user rather than fail.
     */
    public static String userIdOrSystem() {
        return CURRENT.isBound() ? CURRENT.get().userId() : null;
    }

    /**
     * Runs {@code op} with {@code context} bound to the current scope and returns
     * its result. The auth filters are the intended callers.
     *
     * @param context the authenticated caller to bind
     * @param op      the operation to run within the binding
     * @param <R>     the operation's result type
     * @param <X>     the checked exception the operation may throw
     * @return whatever {@code op} returns
     * @throws X whatever {@code op} throws
     */
    public static <R, X extends Throwable> R runWhere(AuthContext context, ScopedValue.CallableOp<R, X> op) throws X {
        return ScopedValue.where(CURRENT, context).call(op);
    }
}
