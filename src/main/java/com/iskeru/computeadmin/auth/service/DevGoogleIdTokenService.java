package com.iskeru.computeadmin.auth.service;

import com.iskeru.computeadmin.common.UnauthorizedException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Dev/test Google bypass: trusts the supplied credential as a raw email and
 * synthesizes a stable identity from it (no network, no signature check). This is
 * the birthday-rsvp dev login — active under the {@code dev} and {@code test}
 * profiles only; the real {@link GoogleIdTokenServiceImpl} takes over elsewhere.
 *
 * <p>spec-011.
 */
@Service
@Profile({"dev", "test"})
public class DevGoogleIdTokenService implements GoogleIdTokenService {

    @Override
    public GoogleIdentity verify(String credential) {
        if (credential == null || credential.isBlank() || !credential.contains("@")) {
            throw new UnauthorizedException("Dev login expects an email as the credential");
        }
        String email = credential.trim().toLowerCase();
        String name = email.substring(0, email.indexOf('@'));
        // Stable synthetic subject so repeated dev logins map to one AppUser.
        return new GoogleIdentity("dev|" + email, email, name);
    }
}
