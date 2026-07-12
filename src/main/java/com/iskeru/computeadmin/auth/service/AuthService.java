package com.iskeru.computeadmin.auth.service;

import com.iskeru.computeadmin.auth.model.AppUser;
import com.iskeru.computeadmin.auth.repository.AppUserRepository;
import com.iskeru.computeadmin.common.UnauthorizedException;
import jakarta.ws.rs.BadRequestException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Self-service email + password sign-in: {@link #register} creates the
 * {@link AppUser} (bcrypt-hashed password) and {@link #login} verifies against the
 * stored hash; both mint the app JWT. This is the only entry point that creates
 * users. Downstream (JWT, personal tokens, pairing, ownership) is unchanged — it
 * reads only {@code userId}/{@code email} and is agnostic to how the user signed in.
 *
 * <p>Password policy (manual validation, no Bean Validation): min 8 chars, capped
 * at 200 so bcrypt's 72-byte truncation is not surprising. Email is normalized
 * {@code trim().toLowerCase()} before store/lookup. Login never leaks which of the
 * two factors failed: unknown email and wrong password both throw the same generic
 * 401 (the repo's 404/401 rule).
 *
 * <p>spec-011; email+password since spec-014.
 */
@Service
public class AuthService {

    private static final int PASSWORD_MIN = 8;
    private static final int PASSWORD_MAX = 200;

    /** Outcome of a register/login: the minted JWT and the resolved user. */
    public record AuthResult(String token, AppUser user) {
    }

    private final AppUserRepository users;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    public AuthService(AppUserRepository users,
                       JwtService jwtService,
                       PasswordEncoder passwordEncoder) {
        this.users = users;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public AuthResult register(String email, String password, String name) {
        if (email == null || email.isBlank() || !email.contains("@")) {
            throw new BadRequestException("a valid email is required");
        }
        if (password == null || password.length() < PASSWORD_MIN || password.length() > PASSWORD_MAX) {
            throw new BadRequestException(
                    "password must be between " + PASSWORD_MIN + " and " + PASSWORD_MAX + " characters");
        }
        String normalized = normalize(email);
        if (users.findByEmail(normalized).isPresent()) {
            throw new DuplicateEmailException(normalized);
        }
        AppUser user = new AppUser();
        user.setEmail(normalized);
        user.setName(displayName(name, normalized));
        user.setPasswordHash(passwordEncoder.encode(password));
        user = users.save(user);
        return new AuthResult(jwtService.mint(user), user);
    }

    @Transactional
    public AuthResult login(String email, String password) {
        String normalized = email == null ? "" : normalize(email);
        AppUser user = users.findByEmail(normalized)
                .filter(u -> password != null && passwordEncoder.matches(password, u.getPasswordHash()))
                .orElseThrow(() -> new UnauthorizedException("Invalid email or password"));
        return new AuthResult(jwtService.mint(user), user);
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase();
    }

    /** Falls back to the email local-part so {@code UserView.name} stays populated. */
    private static String displayName(String name, String normalizedEmail) {
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        return normalizedEmail.substring(0, normalizedEmail.indexOf('@'));
    }
}
