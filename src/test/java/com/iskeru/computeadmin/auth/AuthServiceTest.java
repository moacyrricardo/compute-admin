package com.iskeru.computeadmin.auth;

import com.iskeru.computeadmin.auth.model.AppUser;
import com.iskeru.computeadmin.auth.repository.AppUserRepository;
import com.iskeru.computeadmin.auth.service.AuthService;
import com.iskeru.computeadmin.auth.service.DuplicateEmailException;
import com.iskeru.computeadmin.auth.service.JwtService;
import com.iskeru.computeadmin.common.UnauthorizedException;
import com.iskeru.computeadmin.config.PasswordEncoderConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Email+password sign-in: register self-registers a user and mints a JWT, login
 * verifies the stored hash, a wrong password and an unknown email both fail 401
 * with the same generic message, a duplicate email is 409, and the stored password
 * is a bcrypt hash — never the plaintext.
 *
 * <p>spec-014 (rewrites the spec-011 Google-login test).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({AuthService.class, JwtService.class, PasswordEncoderConfig.class})
class AuthServiceTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void register_NewEmail_CreatesUserAndMintsJwt() {
        AuthService.AuthResult result = authService.register("Ada@Example.com", "correct horse", "Ada");

        AppUser user = result.user();
        assertThat(user.getId()).isNotBlank();
        assertThat(user.getEmail()).isEqualTo("ada@example.com");
        assertThat(user.getName()).isEqualTo("Ada");
        assertThat(users.findByEmail("ada@example.com")).isPresent();

        JwtService.Claim claim = jwtService.verify(result.token());
        assertThat(claim.userId()).isEqualTo(user.getId());
        assertThat(claim.email()).isEqualTo("ada@example.com");
    }

    @Test
    void login_CorrectPassword_ReturnsJwt() {
        authService.register("grace@example.com", "hopper-1906", null);

        AuthService.AuthResult result = authService.login("Grace@Example.com", "hopper-1906");

        JwtService.Claim claim = jwtService.verify(result.token());
        assertThat(claim.email()).isEqualTo("grace@example.com");
    }

    @Test
    void login_WrongPassword_Throws401() {
        authService.register("grace@example.com", "hopper-1906", null);

        assertThatThrownBy(() -> authService.login("grace@example.com", "wrong-password"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid email or password");
    }

    @Test
    void login_UnknownEmail_Throws401() {
        // Same generic message as a wrong password: existence is never leaked.
        assertThatThrownBy(() -> authService.login("nobody@example.com", "whatever-8"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid email or password");
    }

    @Test
    void register_DuplicateEmail_Throws409() {
        authService.register("dup@example.com", "first-password", null);

        assertThatThrownBy(() -> authService.register("Dup@Example.com", "second-password", null))
                .isInstanceOf(DuplicateEmailException.class);
    }

    @Test
    void register_StoresBcryptHashNotPlaintext() {
        String plaintext = "super-secret-1";
        String id = authService.register("hash@example.com", plaintext, null).user().getId();

        AppUser reloaded = users.findById(id).orElseThrow();
        assertThat(reloaded.getPasswordHash()).isNotEqualTo(plaintext);
        assertThat(reloaded.getPasswordHash()).startsWith("$2");
        assertThat(passwordEncoder.matches(plaintext, reloaded.getPasswordHash())).isTrue();
    }
}
