package com.iskeru.computeadmin.auth.service;

import com.iskeru.computeadmin.auth.model.AppUser;
import com.iskeru.computeadmin.common.UnauthorizedException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Mints and verifies the app JWT that authenticates the UI session: subject =
 * {@code userId}, with an {@code email} claim. HMAC-SHA256 signed with the secret
 * from {@code ca.auth.jwt-secret}. The one place JWTs are created; the one place
 * (besides {@code JwtScopeFilter}) they are parsed.
 *
 * <p>spec-011.
 */
@Service
public class JwtService {

    /** Parsed identity claims of a valid app JWT. */
    public record Claim(String userId, String email) {
    }

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(@Value("${ca.auth.jwt-secret:dev-only-insecure-secret-change-me-0123456789}") String secret,
                      @Value("${ca.auth.jwt-ttl-hours:168}") long ttlHours) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException("ca.auth.jwt-secret must be at least 32 bytes for HS256");
        }
        this.key = Keys.hmacShaKeyFor(keyBytes);
        this.ttl = Duration.ofHours(ttlHours);
    }

    /** Mints a signed JWT for {@code user}. */
    public String mint(AppUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getId())
                .claim("email", user.getEmail())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /**
     * Verifies {@code jwt} and returns its identity claims.
     *
     * @throws UnauthorizedException (401) if the token is missing, malformed,
     *                               expired, or fails signature verification.
     */
    public Claim verify(String jwt) {
        if (jwt == null || jwt.isBlank()) {
            throw new UnauthorizedException("Missing JWT");
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(jwt)
                    .getPayload();
            return new Claim(claims.getSubject(), claims.get("email", String.class));
        } catch (JwtException | IllegalArgumentException e) {
            throw new UnauthorizedException("Invalid JWT");
        }
    }
}
