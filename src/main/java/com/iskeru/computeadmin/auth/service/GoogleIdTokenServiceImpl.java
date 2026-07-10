package com.iskeru.computeadmin.auth.service;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.iskeru.computeadmin.common.UnauthorizedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collections;

/**
 * Real Google sign-in verifier: validates a Google ID token's signature, issuer,
 * expiry, and (when configured) audience against Google's published keys, then
 * returns the proven identity. Active outside the {@code dev}/{@code test}
 * profiles, where {@link DevGoogleIdTokenService} takes over.
 *
 * <p>spec-011.
 */
@Service
@Profile({"!dev & !test"})
public class GoogleIdTokenServiceImpl implements GoogleIdTokenService {

    private final GoogleIdTokenVerifier verifier;

    public GoogleIdTokenServiceImpl(@Value("${ca.auth.google-client-id:}") String clientId) {
        GoogleIdTokenVerifier.Builder builder =
                new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance());
        if (clientId != null && !clientId.isBlank()) {
            builder.setAudience(Collections.singletonList(clientId));
        }
        this.verifier = builder.build();
    }

    @Override
    public GoogleIdentity verify(String credential) {
        if (credential == null || credential.isBlank()) {
            throw new UnauthorizedException("Missing Google credential");
        }
        GoogleIdToken token;
        try {
            token = verifier.verify(credential);
        } catch (GeneralSecurityException | IOException e) {
            throw new UnauthorizedException("Google credential verification failed");
        }
        if (token == null) {
            throw new UnauthorizedException("Invalid Google credential");
        }
        GoogleIdToken.Payload payload = token.getPayload();
        return new GoogleIdentity(payload.getSubject(), payload.getEmail(), (String) payload.get("name"));
    }
}
