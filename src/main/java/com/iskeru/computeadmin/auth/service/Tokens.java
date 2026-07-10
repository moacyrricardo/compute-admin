package com.iskeru.computeadmin.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Pure helpers for secret generation and hashing shared by the token and pairing
 * services: high-entropy URL-safe secrets, SHA-256 hex digests (what we store
 * instead of plaintext secrets), and the human-readable pairing user code.
 *
 * <p>spec-011.
 */
final class Tokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    /** No look-alikes (no I/O/0/1) so a user can read the code aloud. */
    private static final char[] USER_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private Tokens() {
    }

    /** A high-entropy URL-safe secret (256 bits). */
    static String randomSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return URL_ENCODER.encodeToString(bytes);
    }

    /** SHA-256 hex digest of {@code value} — what we persist for a secret. */
    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** A short human-readable pairing code, e.g. {@code ABCD-1234}. */
    static String userCode() {
        StringBuilder sb = new StringBuilder(9);
        for (int i = 0; i < 8; i++) {
            if (i == 4) {
                sb.append('-');
            }
            sb.append(USER_CODE_ALPHABET[RANDOM.nextInt(USER_CODE_ALPHABET.length)]);
        }
        return sb.toString();
    }
}
