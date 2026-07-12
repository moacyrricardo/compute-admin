package com.iskeru.computeadmin.auth.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.iskeru.computeadmin.auth.model.AppUser;
import com.iskeru.computeadmin.auth.model.PairingRequest;
import com.iskeru.computeadmin.auth.model.PersonalToken;
import com.iskeru.computeadmin.auth.service.PairingService;
import com.iskeru.computeadmin.auth.service.PersonalTokenService;

import java.time.Instant;

/**
 * DTO records for the {@code auth} REST surface. Request records are plain;
 * response records own their mapping via a static {@code of(...)}. No mapper
 * framework.
 *
 * <p>spec-011.
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    // --- auth ---------------------------------------------------------------

    /** {@code POST /api/auth/register} body: email, password, optional display name. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RegisterRequest(String email, String password, String name) {
    }

    /**
     * {@code POST /api/auth/login} body: email + password. Unknown fields are ignored
     * (defense-in-depth: a stray client field must never fail sign-in — see the login
     * regression in {@code AuthWebTest}).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LoginRequest(String email, String password) {
    }

    /** A signed-in user, safe to expose. */
    public record UserView(String id, String email, String name) {
        public static UserView of(AppUser user) {
            return new UserView(user.getId(), user.getEmail(), user.getName());
        }
    }

    /** Login result: the app JWT plus the resolved user. */
    public record Session(String token, UserView user) {
        public static Session of(String token, AppUser user) {
            return new Session(token, UserView.of(user));
        }
    }

    // --- personal tokens ----------------------------------------------------

    /** {@code POST /api/tokens} body. */
    public record TokenCreate(String label) {
    }

    /** Token metadata — never carries the secret. */
    public record TokenView(String id, String label, Instant createdAt,
                            Instant lastUsedAt, Instant revokedAt) {
        public static TokenView of(PersonalToken token) {
            return new TokenView(token.getId(), token.getLabel(), token.getCreatedAt(),
                    token.getLastUsedAt(), token.getRevokedAt());
        }
    }

    /** The one-time create response: the plaintext is shown exactly here. */
    public record CreatedTokenView(String id, String label, String token) {
        public static CreatedTokenView of(PersonalTokenService.CreatedToken created) {
            return new CreatedTokenView(created.token().getId(), created.token().getLabel(),
                    created.plaintext());
        }
    }

    // --- pairing ------------------------------------------------------------

    /** What the {@code /setup} page shows for a pending pairing request. */
    public record PairingView(String userCode, String status) {
        public static PairingView of(PairingRequest request) {
            return new PairingView(request.getUserCode(), request.getStatus().name());
        }
    }

    /** RFC 8628-shaped response of a device-authorization begin. */
    public record PairingBegin(String deviceCode, String userCode, String verificationUrl,
                               long expiresIn, long interval) {
        public static PairingBegin of(PairingService.BeginResult result) {
            return new PairingBegin(result.deviceCode(), result.userCode(), result.verificationUrl(),
                    result.expiresIn(), result.interval());
        }
    }
}
