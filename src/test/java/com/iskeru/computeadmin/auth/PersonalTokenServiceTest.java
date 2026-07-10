package com.iskeru.computeadmin.auth;

import com.iskeru.computeadmin.auth.model.AppUser;
import com.iskeru.computeadmin.auth.model.PersonalToken;
import com.iskeru.computeadmin.auth.repository.AppUserRepository;
import com.iskeru.computeadmin.auth.repository.PersonalTokenRepository;
import com.iskeru.computeadmin.auth.service.PersonalTokenService;
import com.iskeru.computeadmin.common.AuthContext;
import com.iskeru.computeadmin.common.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Personal-token lifecycle: create hands back the plaintext once and stores only
 * a hash, the hash authenticates back to the owner, and revoke invalidates the
 * lookup.
 *
 * <p>spec-011.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import(PersonalTokenService.class)
class PersonalTokenServiceTest {

    @Autowired
    private PersonalTokenService tokenService;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private PersonalTokenRepository tokens;

    private AppUser owner;

    @BeforeEach
    void seedUser() {
        owner = new AppUser();
        owner.setEmail("owner@example.com");
        owner.setGoogleSub("dev|owner@example.com");
        owner.setName("owner");
        owner = users.save(owner);
    }

    private <R> R asOwner(java.util.function.Supplier<R> body) {
        return CurrentUser.runWhere(AuthContext.ui(owner.getId(), owner.getEmail()), body::get);
    }

    @Test
    void create_ReturnsPlaintextOnce_AndStoresOnlyHash() {
        PersonalTokenService.CreatedToken created = asOwner(() -> tokenService.create("laptop"));

        assertThat(created.plaintext()).isNotBlank();
        PersonalToken stored = tokens.findById(created.token().getId()).orElseThrow();
        assertThat(stored.getTokenHash())
                .isNotEqualTo(created.plaintext())
                .hasSize(64); // SHA-256 hex
    }

    @Test
    void authenticate_WithPlaintext_ResolvesToOwner() {
        PersonalTokenService.CreatedToken created = asOwner(() -> tokenService.create("laptop"));

        AppUser resolved = tokenService.authenticate(created.plaintext()).orElseThrow();

        assertThat(resolved.getId()).isEqualTo(owner.getId());
    }

    @Test
    void authenticate_AfterRevoke_IsRejected() {
        PersonalTokenService.CreatedToken created = asOwner(() -> tokenService.create("laptop"));

        asOwner(() -> {
            tokenService.revoke(created.token().getId());
            return null;
        });

        assertThat(tokenService.authenticate(created.plaintext())).isEmpty();
    }

    @Test
    void list_ReturnsOnlyOwnTokens() {
        asOwner(() -> tokenService.create("a"));
        asOwner(() -> tokenService.create("b"));

        assertThat(asOwner(() -> tokenService.list())).hasSize(2);
    }
}
