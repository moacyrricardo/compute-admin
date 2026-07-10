package com.iskeru.computeadmin.auth.service;

/**
 * Port that resolves a Google credential to a verified identity. The real impl
 * ({@link GoogleIdTokenServiceImpl}) verifies a Google ID token against Google's
 * keys; the dev impl ({@link DevGoogleIdTokenService}) trusts a raw email — the
 * birthday-rsvp dev bypass. Swapped by profile-scoped beans, never a conditional.
 *
 * <p>spec-011.
 */
public interface GoogleIdTokenService {

    /** A verified Google identity. */
    record GoogleIdentity(String sub, String email, String name) {
    }

    /**
     * Verifies {@code credential} and returns the identity it proves.
     *
     * @throws com.iskeru.computeadmin.common.UnauthorizedException (401) if the
     *         credential is missing, malformed, or fails verification.
     */
    GoogleIdentity verify(String credential);
}
