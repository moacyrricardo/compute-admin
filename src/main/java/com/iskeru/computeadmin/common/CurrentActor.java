package com.iskeru.computeadmin.common;

import java.util.Optional;

/**
 * Static facade over the request-scoped {@link Actor}. Application code reads the
 * acting caller <strong>only</strong> through this facade — the raw
 * {@link java.lang.ScopedValue} holder is package-private and bound solely by the
 * transport filter via {@link #runWhere}.
 *
 * <p>Foundation for the ambient-actor mechanism every later tool and audited write
 * inherits. Spec 011 upgrades this to an identity-carrying {@code CurrentUser}
 * facade with {@code userId()}/{@code email()}/{@code via()}.
 *
 * <p>spec-002.
 */
public final class CurrentActor {

    /**
     * The request-scoped actor. Package-private on purpose: no application code may
     * touch the raw scope; it reads through {@link #require()}/{@link #optional()}
     * and binding happens only through {@link #runWhere}.
     */
    static final ScopedValue<Actor> CURRENT_ACTOR = ScopedValue.newInstance();

    private CurrentActor() {
    }

    /**
     * The actor bound to the current scope.
     *
     * @throws IllegalStateException if no actor is bound (called outside a request
     *                               scope — e.g. an unbound background thread).
     */
    public static Actor require() {
        if (!CURRENT_ACTOR.isBound()) {
            throw new IllegalStateException("No actor is bound to the current scope");
        }
        return CURRENT_ACTOR.get();
    }

    /** The actor bound to the current scope, or empty when unbound. */
    public static Optional<Actor> optional() {
        return CURRENT_ACTOR.isBound() ? Optional.of(CURRENT_ACTOR.get()) : Optional.empty();
    }

    /**
     * Runs {@code op} with {@code actor} bound to the current scope. The transport
     * filter is the only intended caller — this is where the ambient actor is
     * established for the duration of a request.
     *
     * @param actor the transport label to bind
     * @param op    the operation to run within the binding
     * @param <X>   the checked exception the operation may throw
     * @throws X whatever {@code op} throws
     */
    public static <X extends Throwable> void runWhere(Actor actor, ScopedValue.CallableOp<Void, X> op) throws X {
        ScopedValue.where(CURRENT_ACTOR, actor).call(op);
    }
}
