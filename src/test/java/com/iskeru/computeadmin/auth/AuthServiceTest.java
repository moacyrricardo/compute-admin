package com.iskeru.computeadmin.auth;

import com.iskeru.computeadmin.auth.model.AppUser;
import com.iskeru.computeadmin.auth.repository.AppUserRepository;
import com.iskeru.computeadmin.auth.service.AuthService;
import com.iskeru.computeadmin.auth.service.DevGoogleIdTokenService;
import com.iskeru.computeadmin.auth.service.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Google login self-registers a new user, returns the same one on a second login,
 * and the minted JWT round-trips back to that user.
 *
 * <p>spec-011.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({AuthService.class, JwtService.class, DevGoogleIdTokenService.class})
class AuthServiceTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private AppUserRepository users;

    @Test
    void loginWithGoogle_FirstTime_SelfRegistersUser() {
        AuthService.AuthResult result = authService.loginWithGoogle("Ada@Example.com");

        AppUser user = result.user();
        assertThat(user.getId()).isNotBlank();
        assertThat(user.getEmail()).isEqualTo("ada@example.com");
        assertThat(users.findByEmail("ada@example.com")).isPresent();
    }

    @Test
    void loginWithGoogle_SecondTime_ReturnsExistingUser() {
        String firstId = authService.loginWithGoogle("ada@example.com").user().getId();
        String secondId = authService.loginWithGoogle("ada@example.com").user().getId();

        assertThat(secondId).isEqualTo(firstId);
        assertThat(users.findAll()).hasSize(1);
    }

    @Test
    void mintedJwt_WhenVerified_RoundTripsToTheUser() {
        AuthService.AuthResult result = authService.loginWithGoogle("grace@example.com");

        JwtService.Claim claim = jwtService.verify(result.token());

        assertThat(claim.userId()).isEqualTo(result.user().getId());
        assertThat(claim.email()).isEqualTo("grace@example.com");
    }
}
