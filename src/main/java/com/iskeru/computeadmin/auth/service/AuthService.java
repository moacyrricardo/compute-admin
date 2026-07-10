package com.iskeru.computeadmin.auth.service;

import com.iskeru.computeadmin.auth.model.AppUser;
import com.iskeru.computeadmin.auth.repository.AppUserRepository;
import com.iskeru.computeadmin.auth.service.GoogleIdTokenService.GoogleIdentity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Google sign-in: verify the credential, find-or-create the {@link AppUser} by
 * Google subject (first login self-registers), and mint the app JWT. This is the
 * only entry point that creates users.
 *
 * <p>spec-011.
 */
@Service
public class AuthService {

    /** Outcome of a login: the minted JWT and the resolved user. */
    public record AuthResult(String token, AppUser user) {
    }

    private final GoogleIdTokenService googleIdTokenService;
    private final AppUserRepository users;
    private final JwtService jwtService;

    public AuthService(GoogleIdTokenService googleIdTokenService,
                       AppUserRepository users,
                       JwtService jwtService) {
        this.googleIdTokenService = googleIdTokenService;
        this.users = users;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthResult loginWithGoogle(String credential) {
        GoogleIdentity identity = googleIdTokenService.verify(credential);
        AppUser user = users.findByGoogleSub(identity.sub())
                .orElseGet(() -> register(identity));
        return new AuthResult(jwtService.mint(user), user);
    }

    private AppUser register(GoogleIdentity identity) {
        AppUser user = new AppUser();
        user.setGoogleSub(identity.sub());
        user.setEmail(identity.email());
        user.setName(identity.name());
        return users.save(user);
    }
}
